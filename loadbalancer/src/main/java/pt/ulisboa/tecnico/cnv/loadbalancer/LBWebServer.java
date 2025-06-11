package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;



public class LBWebServer {
    public static void main(String[] args) throws Exception {
        DynamoDBWriter.setup();          // Set up DynamoDB tables
        startAutoScalerScheduler();      // Start auto scaling scheduler
        startHttpServer();               // Start load balancer HTTP server
        addShutdownHook();               // Attach graceful shutdown hook
    }

    private static void startHttpServer() throws Exception {
        LambdaInstance lambdaInstance = new LambdaInstance();
        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new LBRootHandler());
        server.createContext("/gameoflife", new LBGameOfLifeHandler(lambdaInstance));
        server.createContext("/fifteenpuzzle", new LBFifteenPuzzleHandler(lambdaInstance));
        server.createContext("/capturetheflag", new LBCaptureTheFlagHandler(lambdaInstance));
        server.start();
    }

    private static void startAutoScalerScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                AutoScaler.getInstance().evaluateAndScale();
            } catch (Exception e) {
                System.err.println("Error during scaling: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, 30, TimeUnit.SECONDS); // call auto scaler every 30 seconds
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown detected.  Terminating all active EC2 instances...");

            try {
                AutoScaler.getInstance().terminateAllInstances();
                LambdaInstance.deregisterLambdaFunctions();
            } catch (Exception e) {
                System.err.println("Shutdown cleanup failed:");
                e.printStackTrace();
            }

            System.out.println("Cleanup complete.");
        }));
    }
}
