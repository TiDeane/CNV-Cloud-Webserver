package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

public class LBWebServer {
    public static void main(String[] args) throws Exception {
        DynamoDBWriter.setup(); // sets up DynamoDB tables
        startAutoScalerScheduler(); // starts up auto scaling

        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new LBRootHandler());
        server.createContext("/gameoflife", new LBGameOfLifeHandler());
        server.createContext("/fifteenpuzzle", new LBFifteenPuzzleHandler());
        server.createContext("/capturetheflag", new LBCaptureTheFlagHandler());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown detected. Terminating all active EC2 instances...");

            try {
                AutoScaler.getInstance().terminateAllInstances();
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("Cleanup complete.");
        }));
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
}
