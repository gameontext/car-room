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

public enum CarDirection {   
    LEFT(0, -100), RIGHT(0, 100), FORWARDS(20, 0), BACKWARDS(-75, 0);
    
    CarDirection(long throttle, long lock) {
        this.lock = lock;
        this.throttle = throttle;
    }
    
    private final long lock;
    private final long throttle;
    
    String toJSON() {
        return "'throttle':" + throttle + ",'turning':" + lock;
    }
}
