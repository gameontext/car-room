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
package net.wasdev.gameon.protocol;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.websocket.Session;

public class EventBuilder {
    private final static String FULLNAME = "fullName";
    private final static String DESCRIPTION = "description";
    private final static String LOCATION = "location";
    private final static String NAME = "name";
    
    private static final AtomicInteger counter = new AtomicInteger(0);

    private static void generateEvent(Session session, JsonObject content, String userID, boolean selfOnly, int bookmark)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add("type", "event");
        response.add("content", content);
        response.add("bookmark", bookmark);

        String msg = "player," + (selfOnly ? userID : "*") + "," + response.build().toString();
        session.getBasicRemote().sendText(msg);
    }

    public static void playerEvent(Collection<Session> activeSessions, String senderId, String selfMessage, String othersMessage) {
        // System.out.println("Player message :: from("+senderId+")
        // onlyForSelf("+String.valueOf(selfMessage)+")
        // others("+String.valueOf(othersMessage)+")");
        JsonObjectBuilder content = Json.createObjectBuilder();
        boolean selfOnly = true;
        if (othersMessage != null && othersMessage.length() > 0) {
            content.add("*", othersMessage);
            selfOnly = false;
        }
        if (selfMessage != null && selfMessage.length() > 0) {
            content.add(senderId, selfMessage);
        }
        JsonObject json = content.build();
        int count = counter.incrementAndGet();
        for (Session session : activeSessions) {
            try {
                generateEvent(session, json, senderId, selfOnly, count);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }
    }

    private static void generateRoomEvent(Session session, JsonObject content, int bookmark) throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add("type", "event");
        response.add("content", content);
        response.add("bookmark", bookmark);

        String msg = "player,*," + response.build().toString();

        session.getBasicRemote().sendText(msg);
    }

    public static void roomEvent(Collection<Session> activeSessions, String s) {
        // System.out.println("Message sent to everyone :: "+s);
        JsonObjectBuilder content = Json.createObjectBuilder();
        content.add("*", s);
        JsonObject json = content.build();
        int count = counter.incrementAndGet();
        for (Session session : activeSessions) {
            try {
                generateRoomEvent(session, json, count);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }
    }

    //convenience method
    public static void chatEvent(Session activeSession, String username, String msg) {
        chatEvent(Collections.singleton(activeSession), username, msg);
    }
    
    public static void chatEvent(Collection<Session> activeSessions, String username, String msg) {
        JsonObjectBuilder content = Json.createObjectBuilder();
        content.add("type", "chat");
        content.add("username", username);
        content.add("content", msg);
        content.add("bookmark", counter.incrementAndGet());
        JsonObject json = content.build();
        for (Session session : activeSessions) {
            try {
                String cmsg = "player,*," + json.toString();
                session.getBasicRemote().sendText(cmsg);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }
    }

    public static void locationEvent(Collection<Session> activeSessions, String senderId, String roomId, String roomName, String roomDescription, Map<String,String> exits,
            List<String> objects, List<String> inventory, Map<String,String> commands) {
        JsonObjectBuilder content = Json.createObjectBuilder();
        content.add("type", LOCATION);
        content.add(NAME, roomId);
        content.add(FULLNAME, roomName);
        content.add(DESCRIPTION, roomDescription);
        
        JsonObjectBuilder exitJson = Json.createObjectBuilder();
        for (Entry<String, String> e : exits.entrySet()) {
            exitJson.add(e.getKey().toUpperCase(), e.getValue());
        }
        content.add("exits", exitJson.build());
        
        JsonObjectBuilder commandJson = Json.createObjectBuilder();
        for (Entry<String, String> c : commands.entrySet()) {
            commandJson.add(c.getKey(), c.getValue());
        }
        content.add("commands", commandJson.build());
        
        JsonArrayBuilder inv = Json.createArrayBuilder();
        for (String i : inventory) {
            inv.add(i);
        }
        content.add("pockets", inv.build());
        
        JsonArrayBuilder objs = Json.createArrayBuilder();
        for (String o : objects) {
            objs.add(o);
        }
        content.add("objects", objs.build());
        content.add("bookmark", counter.incrementAndGet());

        JsonObject json = content.build();
        for (Session session : activeSessions) {
            try {
                String lmsg = "player," + senderId + "," + json.toString();
                session.getBasicRemote().sendText(lmsg);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }
    }

    public static void exitEvent(Collection<Session> activeSessions, String senderId, String message, String exitID, String exitJson) {
        JsonObjectBuilder content = Json.createObjectBuilder();
        content.add("type", "exit");
        content.add("exitId", exitID);
        content.add("content", message);
        content.add("bookmark", counter.incrementAndGet());
        JsonObject json = content.build();
        for (Session session : activeSessions) {
            try {
                String emsg = "playerLocation," + senderId + "," + json.toString();
                session.getBasicRemote().sendText(emsg);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }
    }


}
