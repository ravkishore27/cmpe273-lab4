package edu.sjsu.cmpe.cache.client;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.*;
import java.lang.InterruptedException;
import java.io.*;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.http.options.Options;


public class CRDTClient implements CRDTCallbackInterface {

    private ConcurrentHashMap<String, CacheServiceInterface> Server;
    private ArrayList<String> successServer;
    private ConcurrentHashMap<String, ArrayList<String>> dictResults;

    private static CountDownLatch countDownLatch;

    public CRDTClient() {

        Server = new ConcurrentHashMap<String, CacheServiceInterface>(3);
        CacheServiceInterface cache0 = new DistributedCacheService("http://localhost:3000", this);
        CacheServiceInterface cache1 = new DistributedCacheService("http://localhost:3001", this);
        CacheServiceInterface cache2 = new DistributedCacheService("http://localhost:3002", this);
        Server.put("http://localhost:3000", cache0);
        Server.put("http://localhost:3001", cache1);
        Server.put("http://localhost:3002", cache2);
    }

    // Callbacks
    @Override
    public void putFailed(Exception e) {
        System.out.println("The request has failed");
        countDownLatch.countDown();
    }

    @Override
    public void putCompleted(HttpResponse<JsonNode> response, String serverUrl) {
        int getCode = response.getCode();
        System.out.println("completed the put response! code =>" + getCode + " on server =>" + serverUrl);
        successServer.add(serverUrl);
        countDownLatch.countDown();
    }

    @Override
    public void getFailed(Exception e) {
        System.out.println("The request has failed");
        countDownLatch.countDown();
    }

    @Override
    public void getCompleted(HttpResponse<JsonNode> response, String serverUrl) {

        String value = null;
        if (response != null && response.getCode() == 200) {
            value = response.getBody().getObject().getString("value");
                System.out.println("value from server =>" + serverUrl + "is =>" + value);
            ArrayList ServerWithValue = dictResults.get(value);
            if (ServerWithValue == null) {
                ServerWithValue = new ArrayList(3);
            }
            ServerWithValue.add(serverUrl);

            // Save Arraylist of servers into dictResults
            dictResults.put(value, ServerWithValue);
        }

        countDownLatch.countDown();
    }



    public boolean put(long key, String value) throws InterruptedException {
        successServer = new ArrayList(Server.size());
        countDownLatch = new CountDownLatch(Server.size());

        for (CacheServiceInterface cache : Server.values()) {
            cache.put(key, value);
        }

        countDownLatch.await();

        boolean isSuccess = Math.round((float)successServer.size() / Server.size()) == 1;

        if (! isSuccess) {
            // Send delete for the same key
            delete(key, value);
        }
        return isSuccess;
    }

    public void delete(long key, String value) {

        for (final String serverUrl : successServer) {
            CacheServiceInterface server = Server.get(serverUrl);
            server.delete(key);
        }
    }
    public String get(long key) throws InterruptedException {
        dictResults = new ConcurrentHashMap<String, ArrayList<String>>();
        countDownLatch = new CountDownLatch(Server.size());

        for (final CacheServiceInterface server : Server.values()) {
            server.get(key);
        }
        countDownLatch.await();

        // Take the first element
        String rightValue = dictResults.keys().nextElement();

        // Discrepancy in results (either more than one value gotten, or null gotten somewhere)
        if (dictResults.keySet().size() > 1 || dictResults.get(rightValue).size() != Server.size()) {

            ArrayList<String> maxValues = maxKeyForTable(dictResults);

            if (maxValues.size() == 1) {

                rightValue = maxValues.get(0);

                ArrayList<String> repairServer = new ArrayList(Server.keySet());
                repairServer.removeAll(dictResults.get(rightValue));
                for (String serverUrl : repairServer) {

                    System.out.println("repairing: " + serverUrl + " value: " + rightValue);
                    CacheServiceInterface server = Server.get(serverUrl);
                    server.put(key, rightValue);

                }

            } else {

            }
        }

        return rightValue;

    }


    // Returns array of keys with the maximum value
    // If array contains only 1 value, then it is the highest value in the hash map
    public ArrayList<String> maxKeyForTable(ConcurrentHashMap<String, ArrayList<String>> table) {
        ArrayList<String> maxKeys= new ArrayList<String>();
        int maxValue = -1;
        for(Map.Entry<String, ArrayList<String>> entry : table.entrySet()) {
            if(entry.getValue().size() > maxValue) {
                maxKeys.clear(); 
                maxKeys.add(entry.getKey());
                maxValue = entry.getValue().size();
            }
            else if(entry.getValue().size() == maxValue)
            {
                maxKeys.add(entry.getKey());
            }
        }
        return maxKeys;
    }
}