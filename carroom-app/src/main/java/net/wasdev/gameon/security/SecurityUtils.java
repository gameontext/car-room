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
package net.wasdev.gameon.security;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

public class SecurityUtils {

    /**
     * The gameon-signature method requires a hmac hash, this method calculates it.
     * @param stuffToHash List of string values to apply to the hmac
     * @param key The key to init the hmac with
     * @return The hmac as a base64 encoded string.
     * @throws NoSuchAlgorithmException if HmacSHA256 is not found
     * @throws InvalidKeyException Should not be thrown unless there are internal hmac issues.
     * @throws UnsupportedEncodingException If the keystring or hash string are not UTF-8
     */
    public static String buildHmac(List<String> stuffToHash, String key) throws NoSuchAlgorithmException, InvalidKeyException, UnsupportedEncodingException{
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));

        StringBuffer hashData = new StringBuffer();
        for(String s: stuffToHash){
            hashData.append(s);
        }

        return Base64.getEncoder().encodeToString( mac.doFinal(hashData.toString().getBytes("UTF-8")) );
    }

    /**
     * The gameon-sig-body header requires the sha256 hash of the body content. This method calculates it.
     * @param data The string to hash
     * @return the sha256 hash as a base64 encoded string
     * @throws NoSuchAlgorithmException If SHA-256 is not found
     * @throws UnsupportedEncodingException If the String is not UTF-8
     */
    public static String buildHash(String data) throws NoSuchAlgorithmException, UnsupportedEncodingException{
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data.getBytes("UTF-8"));
        byte[] digest = md.digest();
        return Base64.getEncoder().encodeToString( digest );
    }



}
