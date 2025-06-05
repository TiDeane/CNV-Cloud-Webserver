package pt.ulisboa.tecnico.cnv.common;

import pt.ulisboa.tecnico.cnv.javassist.tools.MetricsExtractor;

import java.util.HashMap;
import java.util.Map;

import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;


public class RequestLifecycleManager {

    /* Registers a new request in the ongoing requests table */
    public static void registerNewRequest(String args, String instanceId, String requestId, long nInstructions, long nMemAccesses, DynamoDBWriter dbWriter) {
        dbWriter.registerNewRequest(instanceId, requestId, nInstructions, nMemAccesses);
    }

    /* Removes a request from the ongoing requests table */
    public static void deregisterRequest(String instanceId, String requestId, DynamoDBWriter dbWriter) {
        dbWriter.deregisterRequest(instanceId, requestId);
    }

    /* Stores a finished request in the request metrics table */
    public static void storeFinishedRequest(String args, String handler, String requestId, DynamoDBWriter dbWriter) {
        long nInstructions = MetricsExtractor.getInstructions();
        long nMemAccesses = MetricsExtractor.getTotalMemAccesses();

        dbWriter.writeMetrics(args, handler, nInstructions, nMemAccesses);
    }

    public static String getArgsVector(String query, String handler) {
        Map<String, String> parameters = queryToMap(query);

        switch (handler) {
            case "CaptureTheFlagHandler":
                int gridSize = Integer.parseInt(parameters.get("gridSize"));
                int numBlueAgents = Integer.parseInt(parameters.get("numBlueAgents"));
                int numRedAgents = Integer.parseInt(parameters.get("numRedAgents"));
                char flagPlacementType = parameters.get("flagPlacementType").toUpperCase().charAt(0);
                
                return String.format("[%d,%d,%d,%c]", gridSize, numRedAgents, numBlueAgents, flagPlacementType);
            case "FifteenPuzzleHandler":
                int size = Integer.parseInt(parameters.get("size"));
                int shuffles = Integer.parseInt(parameters.get("shuffles"));

                return String.format("[%d,%d]", size, shuffles);
            case "GameOfLifeHandler":
                int iterations = Integer.parseInt(parameters.get("iterations"));
                String mapFilename = parameters.get("mapFilename");

                return String.format("[%s,%d]", mapFilename, iterations);
            default:
                // unknown handler (shouldn't happen)
                return null;
        }

    }

    private static Map<String, String> queryToMap(String query) {
        if (query == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }

    public static long[] estimateCost(String args) {
        // TODO: develop cost estimation logic
        return new long[] {100000L, 50000L};
    }
}
