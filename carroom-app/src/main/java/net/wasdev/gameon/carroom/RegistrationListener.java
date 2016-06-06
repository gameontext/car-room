/*******************************************************************************
 * Copyright (c) 2016 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.carroom;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.naming.InitialContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import net.wasdev.gameon.security.SecurityUtils;
import net.wasdev.gameon.security.TheNotVerySensibleHostnameVerifier;
import net.wasdev.gameon.security.TheVeryTrustingTrustManager;

@WebListener
public class RegistrationListener implements ServletContextListener {

    private static final String name = "CarRoom";
    private static final String fullName = "A room with a remote control car";
    private static final String description = "There is simple wooden table in the centre of the room, there is the smell of burning rubber in the air.\n\n"
            + "Commands are : \n/left <lock 0 - 100>\n/right <lock 0 - 100>\n/forwards <seconds 0 - 10>\n/backwards <seconds 0 - 10>\n";
    
    List<String> directions = Arrays.asList( "n", "s", "e", "w", "u", "d");

    private final Map<String, String> exits = new HashMap<>();
    private final List<String> objects = new ArrayList<>();
    
    // config values retrieved from JNDI
    // have a look in server.xml to find how these are set
    private final String registrationUrl;
    private final String endPointUrl;
    private final String key;
    private final String userId;
    private final Boolean requiresRegistration;

    public RegistrationListener() {
        registrationUrl = getJNDIEntry("mapSvcUrl");
        System.out.println("Registration endpoint " + registrationUrl);
        
        String url = getJNDIEntry("carSvcUrl");
        if(url == null) {
            try {
                url = System.getenv("HOSTNAME");
                url = "ws://" + InetAddress.getByName(System.getenv("HOSTNAME")).getHostAddress() +":9080/car/carRoom";
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        endPointUrl = url; 
        System.out.println("Websocket endpoint " + endPointUrl);
        key = getJNDIEntry("ownerKey");  
        userId = getJNDIEntry("ownerId");  
        
        requiresRegistration = getBooleanJNDIEntry("requiresRegistration");
        
        
        System.out.println("Requires registration? " + requiresRegistration);
        exits.put("n", "A Large doorway to the north");
        exits.put("s", "A winding path leading off to the south");
        exits.put("e", "An overgrown road, covered in brambles");
        exits.put("w", "A shiny metal door, with a bright red handle");
        exits.put("u", "A spiral set of stairs, leading upward into the ceiling");
        exits.put("d", "A tunnel, leading down into the earth");
        objects.add("Remote control car");
    }

    
    
    private Boolean getBooleanJNDIEntry(String string) {
        try {
            return (Boolean) new InitialContext().lookup(name);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getJNDIEntry(String name) {
        try {
            return (String) new InitialContext().lookup(name);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room registration
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////


    /**
     * Entry point at application start, we use this to test for & perform room registration.
     */
    @Override
    public final void contextInitialized(final ServletContextEvent e) {

        if (requiresRegistration ) {
        // check if we are already registered..
        try {
            configureSSL();

            HttpURLConnection con = isAlreadyRegistered();
            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                //if the result was 200, then we found a room with this id & owner..
                //which is either a previous registration by us, or another room with
                //the same owner & roomname
                //We won't register our room in this case, although we _could_ choose
                //do do an update instead.. (we'd need to parse the json response, and
                //collect the room id, then do a PUT request with our new data.. )
                System.out.println("We are already registered, so updating with a PUT");
                String json = getJSONResponse(con);
                JsonArray array = Json.createReader(new StringReader(json)).readArray();
                JsonString id = array.getJsonObject(0).getJsonString("_id");
                register("PUT", registrationUrl + "/" + id.getString());
            } else {
                register("POST", registrationUrl);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
        }
    }
    
    private HttpURLConnection isAlreadyRegistered() throws Exception {
     // build the query request.
        String queryParams = "name=" + name + "&owner=" + userId;
        
        // build the complete query url..
        System.out.println("Querying room registration using url " + registrationUrl);

        URL u = new URL(registrationUrl + "?" + queryParams );
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        if(registrationUrl.startsWith("https://")) {
            ((HttpsURLConnection)con).setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
        }
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "application/json;");
        con.setRequestProperty("Accept", "application/json,text/plain");
        con.setRequestProperty("Method", "GET");
        return con;
    }
    
    private void configureSSL() {
        TrustManager[] trustManager = new TrustManager[] {new TheVeryTrustingTrustManager()};

        // We don't want to worry about importing the game-on cert into
        // the jvm trust store.. so instead, we'll create an ssl config
        // that no longer cares.
        // This is handy for testing, but for production you'd probably
        // want to go to the effort of setting up a truststore correctly.
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustManager, new java.security.SecureRandom());
        } catch (NoSuchAlgorithmException ex) {
            System.out.println("Error, unable to get algo SSL");
        }catch (KeyManagementException ex) {
            System.out.println("Key management exception!! ");
        }

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }
    
    private void register(String method, String registrationUrl) throws Exception {
        System.out.println("Beginning registration.");
        String registrationPayloadString = getRegistration();

        Instant now = Instant.now();
        String dateValue = now.toString();

        String bodyHash = SecurityUtils.buildHash(registrationPayloadString);

        System.out.println("Building hmac with "+userId+dateValue+bodyHash);
        String hmac = SecurityUtils.buildHmac(Arrays.asList(new String[] {
                                   userId,
                                   dateValue,
                                   bodyHash
                               }),key);


        // build the complete registration url..
        System.out.println("Beginning registration using url " + registrationUrl);
        URL u = new URL(registrationUrl);
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        if(registrationUrl.startsWith("https://")) {
            ((HttpsURLConnection)con).setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
        }
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "application/json;");
        con.setRequestProperty("Accept", "application/json,text/plain");
        con.setRequestProperty("Method", method);
        con.setRequestProperty("gameon-id", userId);
        con.setRequestProperty("gameon-date", dateValue);
        con.setRequestProperty("gameon-sig-body", bodyHash);
        con.setRequestProperty("gameon-signature", hmac);
        con.setRequestMethod(method);
        OutputStream os = con.getOutputStream();

        os.write(registrationPayloadString.getBytes("UTF-8"));
        os.close();

        System.out.println("RegistrationPayload :\n "+registrationPayloadString);

        int httpResult = con.getResponseCode();
        if (httpResult == HttpURLConnection.HTTP_OK || httpResult == HttpURLConnection.HTTP_CREATED) {
            System.out.println("Registration reports success.");
            getJSONResponse(con);
            // here we should remember the exits we're told about,
            // so we can
            // use them when the user does /go direction
            // But we're not dealing with exits here (yet)..
            // user's will have to /sos out of us .. (bad, but ok
            // for now)
        } else {
            System.out.println(
                    "Registration gave http code: " + con.getResponseCode() + " " + con.getResponseMessage());
            // registration sends payload with info why registration
            // failed.
            try (BufferedReader buffer = new BufferedReader(
                    new InputStreamReader(con.getErrorStream(), "UTF-8"))) {
                String response = buffer.lines().collect(Collectors.joining("\n"));
                System.out.println(response);
            }
            System.out.println("Room Registration FAILED .. this room has NOT been registered");
        }
    }
    
    private String getJSONResponse(HttpURLConnection con) throws Exception {
        try (BufferedReader buffer = new BufferedReader(
                new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            String response = buffer.lines().collect(Collectors.joining("\n"));
            System.out.println("Response from server.");
            System.out.println(response);
            return response;
        }
    }
    
    //build the registration JSON for this room
    private String getRegistration() {
        System.out.println("Websocket endpoint " + endPointUrl);

        JsonObjectBuilder registrationPayload = Json.createObjectBuilder();
        // add the basic room info.
        registrationPayload.add("name", name);
        registrationPayload.add("fullName", fullName);
        registrationPayload.add("description", description);
        // add the doorway descriptions we'd like the game to use if it
        // wires us to other rooms.
        JsonObjectBuilder doors = Json.createObjectBuilder();
        for(Entry<String, String> exit : exits.entrySet()) {
            doors.add(exit.getKey(), exit.getValue());
        }
        registrationPayload.add("doors", doors.build());

        // add the connection info for the room to connect back to us..
        JsonObjectBuilder connInfo = Json.createObjectBuilder();
        connInfo.add("type", "websocket"); // the only current supported
                                           // type.
        connInfo.add("target", endPointUrl);
        registrationPayload.add("connectionDetails", connInfo.build());

        return registrationPayload.build().toString();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Here we could deregister, if we wanted.. we'd need to read the registration/query
        // response to cache the room id, so we could remove it as we shut down.
    }


    
}
