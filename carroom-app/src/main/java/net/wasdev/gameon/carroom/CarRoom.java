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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.naming.InitialContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import net.wasdev.gameon.protocol.EventBuilder;
import net.wasdev.gameon.security.SecurityUtils;
import net.wasdev.gameon.security.TheNotVerySensibleHostnameVerifier;
import net.wasdev.gameon.security.TheVeryTrustingTrustManager;

/**
 * A very simple room.
 *
 * The intent of this file is to keep an entire room implementation within one Java file,
 * and to try to minimise its reliance on outside technologies, beyond those required by
 * gameon (WebSockets, Json)
 *
 * Although it would be trivial to refactor out into multiple classes, doing so can make it
 * harder to see 'everything' needed for a room in one go.
 */
@ServerEndpoint("/carRoom")
public class CarRoom {

    private final static String USERNAME = "username";
    private final static String USERID = "userId";
    private final static String BOOKMARK = "bookmark";
    private final static String CONTENT = "content";
    private final static String TYPE = "type";
    private final static String EXIT = "exit";
    private final static String EXIT_ID = "exitId";

    private Set<String> playersInRoom = Collections.synchronizedSet(new HashSet<String>());

    private static final String name = "CarRoom";
    private static final String fullName = "A room with a remote control car";
    private static final String description = "There is simple wooden table in the centre of the room, there is the smell of burning rubber in the air.\n\n"
            + "Commands are : \n/left <lock 0 - 100>\n/right <lock 0 - 100>\n/forwards <seconds 0 - 10>\n/backwards <seconds 0 - 10>\n";
    
    List<String> directions = Arrays.asList( "n", "s", "e", "w", "u", "d");

    private static long bookmark = 0;

    private final Set<Session> sessions = new CopyOnWriteArraySet<Session>();
    private final Map<String, String> exits = new HashMap<>();
    private final List<String> objects = new ArrayList<>();
    
    // config values retrieved from JNDI
    // have a look in server.xml to find how these are set
    private final String carEndPoint;

    public CarRoom() {
        
        carEndPoint = "ws://" + getJNDIEntry("carEndPoint");
        System.out.println("Car endpoint " + carEndPoint);
        
        exits.put("n", "A Large doorway to the north");
        exits.put("s", "A winding path leading off to the south");
        exits.put("e", "An overgrown road, covered in brambles");
        exits.put("w", "A shiny metal door, with a bright red handle");
        exits.put("u", "A spiral set of stairs, leading upward into the ceiling");
        exits.put("d", "A tunnel, leading down into the earth");
        objects.add("Remote control car");
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
    // Websocket methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private Session userSession;
    
    @OnOpen
    public void onOpen(Session session, EndpointConfig ec) {
        System.out.println("A new connection has been made to the room.");
        userSession = session;
        //send ack
        sendRemoteTextMessage(session, "ack,{\"version\":[1]}");
    }

    @OnClose
    public void onClose(Session session, CloseReason r) {
        System.out.println("A connection to the room has been closed");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        if(session!=null){
            sessions.remove(session);
        }
        System.out.println("Websocket connection has broken");
        t.printStackTrace();
    }

    @OnMessage
    public void receiveMessage(String message, Session session) throws IOException {
        String[] contents = splitRouting(message);

        // Who doesn't love switch on strings in Java 8?
        switch(contents[0]) {
            case "roomHello":
                sessions.add(session);
                addNewPlayer(session, contents[2]);
                break;
            case "room":
                processCommand(session, contents[2]);
                break;
            case "roomGoodbye":
                removePlayer(session, contents[2]);
                break;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // add a new player to the room
    private void addNewPlayer(Session session, String json) throws IOException {
        if (session.getUserProperties().get(USERNAME) != null) {
            return; // already seen this user before on this socket
        }
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));

        if (playersInRoom.add(userid)) {
            // broadcast that the user has entered the room
            EventBuilder.playerEvent(Collections.singletonList(session),
                    userid, "You have entered the room", "Player " + username + " has entered the room");
           
            EventBuilder.locationEvent(Collections.singletonList(session),
                    userid, name, fullName, description, exits, objects, Collections.emptyList(), Collections.emptyMap());
        }
    }

    // remove a player from the room.
    private void removePlayer(Session session, String json) throws IOException {
        sessions.remove(session);
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));
        playersInRoom.remove(userid);

        // broadcast that the user has left the room
        sendMessageToRoom(session, "Player " + username + " has left the room", null, userid);
    }

    // process a command
    private void processCommand(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = getValue(msg.get(USERID));
        String username = getValue(msg.get(USERNAME));
        String content = getValue(msg.get(CONTENT)).toString();
        String lowerContent = content.toLowerCase();

        System.out.println("Command received from the user, " + content);

        // handle look command
        if (lowerContent.equals("/look")) {
            // resend the room description when we receive /look
            EventBuilder.locationEvent(Collections.singletonList(session),
                    userid, name, fullName, description, exits, objects, Collections.emptyList(), Collections.emptyMap());
            return;
        }
        
        for(CarDirection direction : CarDirection.values()) {
            String match = "/" + direction.name().toLowerCase() + " ";
            if(lowerContent.startsWith(match)) {
                //this is a command to drive the car
                try {
                    Long value = Long.parseLong(lowerContent.substring(match.length()));
                    if((value < 0) | (value > 100)) {
                        sendMessageToRoom(session, null, "ERROR : The car commands require an integer between 0 and 100", userid);
                    } else {
                        try {
                            sendToCar(userid, direction, value);
                        } catch (NumberFormatException e) {
                            //this is an exception generated by the room with a meaningful message
                            sendMessageToRoom(session, null, e.getMessage(), userid);
                        }
                    }
                } catch (NumberFormatException e) {
                    sendMessageToRoom(session, null, "ERROR : The car commands require an integer as the second parameter", userid);
                }
                return;
            }
        }
        
        if (lowerContent.startsWith("/go")) {
            String exitDirection = null;
            if (lowerContent.length() > 4) {
                exitDirection = lowerContent.substring(4).toLowerCase();
            }

            if ( exitDirection == null || !directions.contains(exitDirection) ) {
                sendMessageToRoom(session, null, "Hmm. That direction didn't make sense. Try again?", userid);
            } else {
                // Trying to go somewhere, eh?
                JsonObjectBuilder response = Json.createObjectBuilder();
                response.add(TYPE, EXIT)
                .add(EXIT_ID, exitDirection)
                .add(BOOKMARK, bookmark++)
                .add(CONTENT, "Run Away!");

                sendRemoteTextMessage(session, "playerLocation," + userid + "," + response.build().toString());
            }
            return;
        }

        // reject all unknown commands
        if (lowerContent.startsWith("/")) {
            sendMessageToRoom(session, null, "Unrecognised command - sorry :-(", userid);
            return;
        }

        // everything else is just chat.
        EventBuilder.chatEvent(session, username, content);
        return;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Reply methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void sendMessageToRoom(Session session, String messageForRoom, String messageForUser, String userid)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "event");

        JsonObjectBuilder content = Json.createObjectBuilder();
        if (messageForRoom != null) {
            content.add("*", messageForRoom);
        }
        if (messageForUser != null) {
            content.add(userid, messageForUser);
        }

        response.add(CONTENT, content.build());
        response.add(BOOKMARK, bookmark++);

        if(messageForRoom==null){
            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
        }else{
            broadcast(sessions, "player,*," + response.build().toString());
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Util fns.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String[] splitRouting(String message) {
        ArrayList<String> list = new ArrayList<>();

        int brace = message.indexOf('{');
        int i = 0;
        int j = message.indexOf(',');
        while (j > 0 && j < brace) {
            list.add(message.substring(i, j));
            i = j + 1;
            j = message.indexOf(',', i);
        }
        list.add(message.substring(i));

        return list.toArray(new String[] {});
    }

    private static String getValue(JsonValue value) {
        if (value.getValueType().equals(ValueType.STRING)) {
            JsonString s = (JsonString) value;
            return s.getString();
        } else {
            return value.toString();
        }
    }

    /**
     * Simple text based broadcast.
     *
     * @param session
     *            Target session (used to find all related sessions)
     * @param message
     *            Message to send
     * @see #sendRemoteTextMessage(Session, RoutedMessage)
     */
    public void broadcast(Set<Session> sessions, String message) {
        for (Session s : sessions) {
            sendRemoteTextMessage(s, message);
        }
    }

    /**
     * Try sending the {@link RoutedMessage} using
     * {@link Session#getBasicRemote()}, {@link Basic#sendObject(Object)}.
     *
     * @param session
     *            Session to send the message on
     * @param message
     *            Message to send
     * @return true if send was successful, or false if it failed
     */
    public boolean sendRemoteTextMessage(Session session, String message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                return true;
            } catch (IOException ioe) {
                // An IOException, on the other hand, suggests the connection is
                // in a bad state.
                System.out.println("Unexpected condition writing message: " + ioe);
                tryToClose(session, new CloseReason(CloseCodes.UNEXPECTED_CONDITION, trimReason(ioe.toString())));
            }
        }
        return false;
    }

    /**
     * {@code CloseReason} can include a value, but the length of the text is
     * limited.
     *
     * @param message
     *            String to trim
     * @return a string no longer than 123 characters.
     */
    private static String trimReason(String message) {
        return message.length() > 123 ? message.substring(0, 123) : message;
    }

    /**
     * Try to close the WebSocket session and give a reason for doing so.
     *
     * @param s
     *            Session to close
     * @param reason
     *            {@link CloseReason} the WebSocket is closing.
     */
    public void tryToClose(Session s, CloseReason reason) {
        try {
            s.close(reason);
        } catch (IOException e) {
            tryToClose(s);
        }
    }

    /**
     * Try to close a {@code Closeable} (usually once an error has already
     * occurred).
     *
     * @param c
     *            Closable to close
     */
    public void tryToClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e1) {
            }
        }
    }
    //****************************************************************
    // Car control methods
    //****************************************************************
    
    private enum CarDirection {   
        LEFT, RIGHT, FORWARDS, BACKWARDS;
    }
    
    private class CarInstruction {
        private final String id = UUID.randomUUID().toString();
        private final CarDirection direction;
        private final String userid;
        private final Long value;
        
        CarInstruction(String userid, CarDirection direction, Long value) throws NumberFormatException {
            this.direction = direction;
            this.userid = userid;
            this.value = value;
            //validate that the value is OK for the direction specified
            switch(direction) {
                case LEFT :
                case RIGHT :
                    if((value < 0) || (value > 100)) {
                        throw new NumberFormatException("ERROR : The left and right commands have an integer value between 0 and 100 (inclusive)");
                    }
                    break;
                case FORWARDS :
                case BACKWARDS :
                    if((value < 0) || (value > 10)) {
                        throw new NumberFormatException("ERROR : The forwards and backwards commands have an integer value between 0 and 10 (inclusive)");
                    }
                    break;
            }
        }
        
        String toJSON() {
            String prefix = "{'id':'" + userid + "', 'msggrp':'" + id + "',";
            String suffix = "}";
            switch(direction) {
                case LEFT :
                    return prefix + "'turning':-" + value  + suffix;
                case RIGHT :
                    return prefix + "'turning':" + value  + suffix;
                case FORWARDS :
                    return prefix + "'throttle':20" + suffix;
                case BACKWARDS :
                    return prefix + "'throttle':-70" + suffix;
                default :
                    return prefix + "'throttle':0,'turning':0"+ suffix;
            }
       }
        
        @Override
        public String toString() {
            return toJSON();
        }
        
        public String getID() {
            return id;
        }
                
    }
    
    private volatile boolean carAvailable = false;
    private Session carSession = null;      //web socket connection with the car
    private final ScheduledExecutorService carController = Executors.newScheduledThreadPool(1);
    private BlockingQueue<CarInstruction> instructions = new LinkedBlockingQueue<>();
        
    private synchronized void sendToCar(String userid, CarDirection direction, Long value) {
        final CarInstruction instruction = new CarInstruction(userid, direction, value);
        if(!isCarAvailable()) {
            return;
        }
        Log.log(Level.INFO, carController, "Sending instruction to car", instruction);
        System.out.println("Queueing instruction to car : " + instruction);
            
        int msgCount = 1;
        if(direction.equals(CarDirection.FORWARDS) || direction.equals(CarDirection.BACKWARDS)) {
            msgCount = (int)(value * 20);      //convert seconds to a 50ms pulse
        }
        
        for(;msgCount > 0; msgCount --) {
            try {
                instructions.put(instruction);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    private boolean isCarAvailable() {
        Log.log(Level.INFO, carController, "Checking connection with car");
        if(!carAvailable) {
            try {
                connectToCar();
                carAvailable = true;
                System.out.println("Car connected OK");
                carController.scheduleAtFixedRate(new Runnable(){
                    @Override
                    public void run() {
                        if(!instructions.isEmpty()) {
                            try {
                                if(isCarAvailable()) {
                                    CarInstruction instruction = instructions.take();
                                    System.out.println("Sending instruction to car : " + instruction);
                                    carSession.getBasicRemote().sendText(instruction.toJSON());
                                } else {
                                    System.out.println("Car not available, rescheduling command");
                                    carController.scheduleWithFixedDelay(this,1000, 50, TimeUnit.SECONDS);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    
                }, 0, 50, TimeUnit.MILLISECONDS);
                System.out.println("Car controller configured");
            } catch (Exception e) {
                Log.log(Level.WARNING, carController, "Unable to connect to car", e);
                e.printStackTrace();   
            }
        }
        return carAvailable;
    }
    
    //connect to the car web socket
    private void connectToCar() throws Exception {
        Log.log(Level.INFO, carController, "Connecting to car at : " + carEndPoint);
        System.out.println("Connecting to car");
        WebSocketContainer c = ContainerProvider.getWebSocketContainer();
        carSession = c.connectToServer(new Endpoint() {

            @Override
            public void onOpen(Session session, EndpointConfig config) {
                Log.log(Level.INFO, carController, "Connected to car at : " + carEndPoint);
                System.out.println("Connected to car : " + carEndPoint);
                session.addMessageHandler(new MessageHandler.Whole<String>() {

                    @Override
                    public void onMessage(String message) {
                        Log.log(Level.INFO, carController, "Message received from car", message);
                        int pos = message.lastIndexOf(' ');
                        if(pos != -1) {
                            try {
                                sendMessageToRoom(userSession, null, message, message.substring(pos + 1));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }   
                        }
                        System.out.println("Message received from car : " + message);
                    }
                    
                });
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                carAvailable = false;
                Log.log(Level.INFO, carController, "Car has closed the connection.", closeReason);
                System.out.println("Car has closed the connection : " + closeReason);
                carController.shutdown();
                System.out.println("Stopped car controller");
            }

            @Override
            public void onError(Session session, Throwable thr) {
                carAvailable = false;
                Log.log(Level.INFO, carController, "Error with car connection.", thr.getMessage());
                System.out.println("Error with car connection : " + thr.getMessage());
                carController.shutdown();
                System.out.println("Stopped car controller");
            }
            
        }, null, new URI(carEndPoint));
    }
    
}
