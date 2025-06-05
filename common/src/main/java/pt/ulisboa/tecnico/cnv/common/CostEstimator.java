package pt.ulisboa.tecnico.cnv.common;

import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Comparator;

import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;

public class CostEstimator {
    
    private DynamoDBWriter dbWriter;

    private final int K = 3;

    public CostEstimator() {
        dbWriter = DynamoDBWriter.getInstance();
    }

    /* Uses KNN to estimate metrics for an unseen set of arguments */
    /* Returns an array with format [nInstructions, nMemAccesses] */
    public long[] estimateMetrics(String args, String handler) {
        List<Map<String, AttributeValue>> items = dbWriter.getHandlerRequestMetrics(handler);

        if (items == null || items.isEmpty()) return new long[]{1000L, 300L}; // fallback

        double[] inputArgs = parseArgs(args, handler);

        PriorityQueue<Neighbor> knn = new PriorityQueue<>(Comparator.comparingDouble(n -> n.distance));

        /* POSSIBLE OPTIMIZATIONS:
         * Instead of only separating by handler name, maybe separate within
         * each handler aswell (for example, for CaptureTheFlagHandler only
         * consider neighbours with the same flagPlacementType)
        */ 

        for (Map<String, AttributeValue> item : items) {
            String itemArgsString = item.get("args").getS();
            double[] itemArgs = parseArgs(itemArgsString, handler);
            if (itemArgs.length != inputArgs.length) continue;

            double dist = euclideanDistance(inputArgs, itemArgs);
            long nInstructions = Long.parseLong(item.get("nInstructions").getN());
            long nMemAccesses = Long.parseLong(item.get("nMemAccesses").getN());

            knn.add(new Neighbor(dist, nInstructions, nMemAccesses));
        }

        // Select K closest
        long totalInstructions = 0;
        long totalMemAccesses = 0;
        int count = 0;

        while (!knn.isEmpty() && count < K) {
            Neighbor neighbor = knn.poll();
            totalInstructions += neighbor.nInstructions;
            totalMemAccesses += neighbor.nMemAccesses;
            count++;
        }

        if (count == 0) return new long[]{1000L, 300L}; // fallback

        long estimatedInstructions = totalInstructions / count;
        long estimatedMemAccesses = totalMemAccesses / count;

        return new long[]{estimatedInstructions, estimatedMemAccesses};
    }

    /* Parses request arguments stored in DynamoDB, returns as array of doubles ready for KNN */
    private double[] parseArgs(String args, String handler) {
        String[] rawArgs = args.replaceAll("[\\[\\]\\s]", "").split(",");
        double[] result;

        switch (handler) {
            case "GameOfLifeHandler":
                result = new double[2];
                // mapFilename
                result[0] = encodeMapFilename(rawArgs[0]);
                // iterations
                result[1] = Double.parseDouble(rawArgs[1]);
                break;

            case "FifteenPuzzleHandler":
                result = new double[2];
                // size
                result[0] = Double.parseDouble(rawArgs[0]);
                // shuffles
                result[1] = Double.parseDouble(rawArgs[1]);
                break;

            case "CaptureTheFlagHandler":
                result = new double[4];
                // gridSize
                result[0] = Double.parseDouble(rawArgs[0]);
                // numBlueAgents
                result[1] = Double.parseDouble(rawArgs[1]);
                // numRedAgents
                result[2] = Double.parseDouble(rawArgs[2]);
                // flagPlacementType
                result[3] = encodeFlagPlacementType(rawArgs[3]);
                break;

            default: // unknown handler (shouldn't happen)
                return null;
        }

        return result;
    }

    private double encodeFlagPlacementType(String flagPlacementType) {
        switch (flagPlacementType) {
            case "A": return 0;
            case "B": return 1;
            case "C": return 2;
            default: return 3; // shouldn't happen
        }
    }

    private double encodeMapFilename(String mapFilename) {
        switch (mapFilename) {
            case "glider-10-10.json": return 0;
            default: return 1; // shouldn't happen
        }
    }

    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.pow(a[i] - b[i], 2);
        }
        return Math.sqrt(sum);
    }
    
    // Used for KNN metrics estimation
    private static class Neighbor {
        double distance;
        long nInstructions;
        long nMemAccesses;

        Neighbor(double distance, long nInstructions, long nMemAccesses) {
            this.distance = distance;
            this.nInstructions = nInstructions;
            this.nMemAccesses = nMemAccesses;
        }
    }
}
