package pt.ulisboa.tecnico.cnv.capturetheflag;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pt.ulisboa.tecnico.cnv.javassist.tools.MetricsExtractor;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CaptureTheFlagHandler implements HttpHandler, RequestHandler<Map<String, String>, String> {

    /**
     * Simulation entrypoint.
     */
    private String handleWorkload(int gridSize, int numBlueAgents, int numRedAgents, char flagPlacementType) {
        try {
            int numFlagsPerTeam = gridSize / 2;
            Simulation simulation = new Simulation();
            simulation.init(gridSize, flagPlacementType, numBlueAgents, numRedAgents, numFlagsPerTeam, false);
            simulation.run();
            return simulation.getData();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Entrypoint or HTTP requests.
     */
    @Override
    public void handle(HttpExchange he) throws IOException {
        // Handling CORS.
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equalsIgnoreCase(he.getRequestMethod())) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            return;
        }

        // Parse request.
        URI requestedUri = he.getRequestURI();
        String query = requestedUri.getRawQuery();
        Map<String, String> parameters = queryToMap(query);

        int gridSize = Integer.parseInt(parameters.get("gridSize"));
        int numBlueAgents = Integer.parseInt(parameters.get("numBlueAgents"));
        int numRedAgents = Integer.parseInt(parameters.get("numRedAgents"));
        char flagPlacementType = parameters.get("flagPlacementType").toUpperCase().charAt(0);

        if (!validateInputs(gridSize, numBlueAgents, numRedAgents, flagPlacementType)) {
            String response = "Invalid input. Please provide a valid grid size, number of blue agents, number of red agents and flag placement type (A, B or C).";
            he.sendResponseHeaders(400, response.length());
            OutputStream os = he.getResponseBody();
            os.write(response.getBytes());
            os.close();
            return;
        }

        String response = handleWorkload(gridSize, numBlueAgents, numRedAgents, flagPlacementType);

        storeRequest(gridSize, numBlueAgents, numRedAgents, flagPlacementType);

        he.sendResponseHeaders(200, response.length());
        OutputStream os = he.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    /**
     * Entrypoint for AWS Lambda.
     */
    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        int gridSize = Integer.parseInt(event.get("gridSize"));
        int numBlueAgents = Integer.parseInt(event.get("numBlueAgents"));
        int numRedAgents = Integer.parseInt(event.get("numRedAgents"));
        char flagPlacementType = event.get("flagPlacementType").toUpperCase().charAt(0);

        if (!validateInputs(gridSize, numBlueAgents, numRedAgents, flagPlacementType)) {
            return "Invalid input. Please provide a valid grid size, number of blue agents, number of red agents and flag placement type (A, B or C).";
        }

        return handleWorkload(gridSize, numBlueAgents, numRedAgents, flagPlacementType);
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java pt.ulisboa.tecnico.cnv.capturetheflag.CaptureTheFlagHandler <grid_size> <num_blue_agents> <num_red_agents> <flag_placement_type>");
            System.out.println("Where: grid_size >= 10; num_blue_agents <= grid_size; num_red_agents <= grid_size; flag_placement_type=A|B|C");
            return;
        }

        int gridSize = Integer.parseInt(args[0]);
        int numBlueAgents = Integer.parseInt(args[1]);
        int numRedAgents = Integer.parseInt(args[2]);
        char flagPlacementType = args[3].toUpperCase().charAt(0);

        if (gridSize < 10) {
            throw new IllegalArgumentException("grid size must be greater or equal to 10");
        }

        if (numBlueAgents > gridSize || numRedAgents > gridSize) {
            throw new IllegalArgumentException("number of agents per team must be lower or equal to the grid size");
        }

        if (!List.of('A', 'B', 'C').contains(flagPlacementType)) {
            throw new IllegalArgumentException("flag placement type must be A, B or C");
        }

        long startTime = System.nanoTime();

        int numFlagsPerTeam = gridSize / 2;
        Simulation simulation = new Simulation();
        simulation.init(gridSize, flagPlacementType, numBlueAgents, numRedAgents, numFlagsPerTeam, true);
        simulation.run();
        System.out.println(simulation.getData());

        long endTime = System.nanoTime();
        System.out.println("[INFO] exec time: " + ((endTime - startTime) / 1_000_000) + " ms");
    }

    /**
     * Parse query string into a map.
     */
    private Map<String, String> queryToMap(String query) {
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

    private boolean validateInputs(int gridSize, int numBlueAgents, int numRedAgents, char flagPlacementType) {
        if (gridSize < 10) {
            return false;
        }

        if (numBlueAgents > gridSize || numRedAgents > gridSize) {
            return false;
        }

        return List.of('A', 'B', 'C').contains(flagPlacementType);
    }

    private void storeRequest(int gridSize, int numBlueAgents, int numRedAgents, char flagPlacementType) {
        DynamoDBWriter dbWriter = new DynamoDBWriter();

        String args = "[" + Integer.toString(gridSize) + "," + Integer.toString(numBlueAgents) + "," +
                            Integer.toString(numRedAgents) + "," + flagPlacementType + "]";
        String handler = "CaptureTheFlagHandler";
        long nInstructions = MetricsExtractor.getInstructions();
        long nMemAccesses = MetricsExtractor.getTotalMemAccesses();

        dbWriter.writeMetrics(args, handler, nInstructions, nMemAccesses);
    }

}
