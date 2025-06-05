package pt.ulisboa.tecnico.cnv.gameoflife;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import pt.ulisboa.tecnico.cnv.common.RequestLifecycleManager;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class GameOfLifeHandler implements HttpHandler, RequestHandler<Map<String, String>, String> {

    private final static ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Input map model.
     */
    private static class GameOfLifeInput {
        public int[][] map;

        public GameOfLifeInput(int[][] map) {
            this.map = map;
        }

        public GameOfLifeInput() {}
    }

    /**
     * Output (response) model.
     */
    private static class GameOfLifeResponse {
        public int[][] inputMap;
        public int[][] outputMap;

        public GameOfLifeResponse(int[][] inputMap, int[][] outputMap) {
            this.inputMap = inputMap;
            this.outputMap = outputMap;
        }

        public GameOfLifeResponse() {}
    }

    /**
     * Game entrypoint.
     */
    private String handleWorkload(int[][] inputMap, int iterations) {
        int height = inputMap.length;
        int width = (height > 0) ? inputMap[0].length : 0;
        byte[] map = convertMapToByteArray(inputMap, height, width);

        GameOfLife gol = new GameOfLife(width, height, map);
        gol.play(iterations);
        byte[] resultData = gol.getData();

        int[][] resultMap = new int[height][width];
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                resultMap[i][j] = Byte.toUnsignedInt(resultData[i * width + j]);
            }
        }
        GameOfLifeResponse response = new GameOfLifeResponse(inputMap, resultMap);

        try {
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return "{ \"error\":\"" + e.getMessage() + "\"}";
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

        int iterations = Integer.parseInt(parameters.get("iterations"));
        String mapFilename = parameters.get("mapFilename");
        String requestId = parameters.getOrDefault("requestId", "---");

        int[][] map;
        try (InputStream mapFileInputStream = getClass().getClassLoader().getResourceAsStream(mapFilename)) {
            GameOfLifeInput request = MAPPER.readValue(mapFileInputStream, GameOfLifeInput.class);
            map = request.map;
        } catch (NullPointerException | JsonProcessingException e) {
            e.printStackTrace();
            String errorResponse = "{ \"error\":\"" + e.getMessage() + "\"}";
            he.sendResponseHeaders(400, errorResponse.length());
            OutputStream os = he.getResponseBody();
            os.write(errorResponse.getBytes());
            os.close();
            return;
        }

        String args = String.format("[%s,%d]", mapFilename, iterations);

        DynamoDBWriter dbWriter = DynamoDBWriter.getInstance();

        String response = handleWorkload(map, iterations);

        RequestLifecycleManager.storeFinishedRequest(args, "GameOfLifeHandler", requestId, dbWriter);

        he.sendResponseHeaders(200, response.length());
        OutputStream os = he.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    /**
     * Entrypoint for AWS Lambda.
     */
    @Override
    public String handleRequest(Map<String,String> event, Context context) {
        int iterations = Integer.parseInt(event.get("iterations"));
        String mapFilename = event.get("mapFilename");

        int[][] map;
        try (InputStream mapFileInputStream = getClass().getClassLoader().getResourceAsStream(mapFilename)) {
            GameOfLifeInput request = MAPPER.readValue(mapFileInputStream, GameOfLifeInput.class);
            map = request.map;
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            return "{ \"error\":\"" + e.getMessage() + "\"}";
        }

        return handleWorkload(map, iterations);
    }

    /**
     * For debugging use - to run from CLI.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java pt.ulisboa.tecnico.cnv.gameoflife.GameOfLifeHandler <map_json_filename> <iterations>");
            return;
        }
        String mapFilename = args[0];

        int[][] intMap;
        try (InputStream mapFileInputStream = GameOfLifeHandler.class.getClassLoader().getResourceAsStream(mapFilename)) {
            GameOfLifeInput request = MAPPER.readValue(mapFileInputStream, GameOfLifeInput.class);
            intMap = request.map;
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            System.exit(1);
            return;  // redundant but needed to avoid null-check warning.
        }

        int iterations = 0;
        try {
            iterations = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("The \"iterations\" argument should be a valid integer value.");
            System.exit(1);
        }

        int rows = intMap.length;
        int cols = (rows > 0) ? intMap[0].length : 0;
        byte[] map = convertMapToByteArray(intMap, rows, cols);

        GameOfLife gol = new GameOfLife(cols, rows, map);

        System.out.println("Initial State:");
        System.out.println(gol.gridToString());

        // You can also use 'gol.playCLI()' for interactive simulation.
        gol.play(iterations);

        System.out.println("Final State:");
        System.out.println(gol.gridToString());
    }

    /**
     * Util method to convert a 2D int array to a byte array.
     */
    private static byte[] convertMapToByteArray(int[][] map, int rows, int cols) {
        byte[] byteArray = new byte[rows * cols];

        int index = 0;
        for (int[] row : map) {
            for (int cell : row) {
                byteArray[index++] = (byte) cell;
            }
        }

        return byteArray;
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

}
