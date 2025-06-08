package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;
import java.util.concurrent.*;
import java.lang.InterruptedException;

import pt.ulisboa.tecnico.cnv.autoscaler.AutoScaler;

public class LBWebServer {
    /*public static void main(String[] args) throws Exception {
        DynamoDBWriter.setup(); // sets up DynamoDB tables
        System.out.println("DB is set");
        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new LBRootHandler());
        server.createContext("/gameoflife", new LBGameOfLifeHandler());
        server.createContext("/fifteenpuzzle", new LBFifteenPuzzleHandler());
        server.createContext("/capturetheflag", new LBCaptureTheFlagHandler());
        server.start();
    }*/
    public static void main(String[] args) throws Exception, InterruptedException {
        DynamoDBWriter.setup();
        System.out.println("DB is set");
        Thread serverThread = new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
                server.setExecutor(Executors.newCachedThreadPool());
                server.createContext("/", new LBRootHandler());
                server.createContext("/gameoflife", new LBGameOfLifeHandler());
                server.createContext("/fifteenpuzzle", new LBFifteenPuzzleHandler());
                server.createContext("/capturetheflag", new LBCaptureTheFlagHandler());
                server.start();
                System.out.println("HTTP Server started.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Thread asThread = new Thread(() -> {
            try {
                AutoScaler as = new AutoScaler();
                //as.start();
                as.launchAnInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
            serverThread.start();
            asThread.start();        
    }



}






