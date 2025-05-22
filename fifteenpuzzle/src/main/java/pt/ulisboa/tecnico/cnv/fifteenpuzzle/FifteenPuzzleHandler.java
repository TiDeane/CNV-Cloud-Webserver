package pt.ulisboa.tecnico.cnv.fifteenpuzzle;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pt.ulisboa.tecnico.cnv.javassist.tools.MetricsExtractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class FifteenPuzzleHandler implements HttpHandler, RequestHandler<Map<String, String>, String> {

    /**
     * Solver entrypoint.
     */
    private String handleWorkload(int size, int shuffles) {
        StringBuilder sb = new StringBuilder();

        FifteenPuzzle puzzle = new FifteenPuzzle(size);
        Random random = new Random(42); // fixed seed
        puzzle.shuffle(shuffles, random);

        sb.append("\nInitial (Shuffled) Board:").append("\n");
        sb.append(puzzle.getData()).append("\n");

        List<FifteenPuzzle> solution = puzzle.idaStarSolve();

        if (solution != null && !solution.isEmpty()) {
            sb.append("\nFinal (Solved) Board:").append("\n");
            sb.append(solution.get(solution.size() - 1).getData()).append("\n");
        }

        sb.append(FifteenPuzzle.getSolutionData(solution)).append("\n");
        return sb.toString();
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

        int size = Integer.parseInt(parameters.get("size"));
        int shuffles = Integer.parseInt(parameters.get("shuffles"));

        String response = handleWorkload(size, shuffles);

        resultsToTxt(size, shuffles); // later on, write to DynamoDB

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
        int size = Integer.parseInt(event.get("size"));
        int shuffles = Integer.parseInt(event.get("shuffles"));

        return handleWorkload(size, shuffles);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java pt.ulisboa.tecnico.cnv.fifteenpuzzle.FifteenPuzzleHandler <size> <number_of_shuffles>");
            return;
        }

        int size = Integer.parseInt(args[0]);
        int shuffles = Integer.parseInt(args[1]);

        FifteenPuzzle puzzle = new FifteenPuzzle(size);
        Random random = new Random(42); // fixed seed
        puzzle.shuffle(shuffles, random);

        System.out.println("\nInitial (Shuffled) Board:");
        System.out.println(puzzle.getData());

        List<FifteenPuzzle> solution = puzzle.idaStarSolve();

        if (solution != null && !solution.isEmpty()) {
            System.out.println("\nFinal (Solved) Board:");
            System.out.println(solution.get(solution.size() - 1).getData());
        }

        System.out.println(FifteenPuzzle.getSolutionData(solution));
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

    private void resultsToTxt(int size, int shuffles) {
        String line = String.format(
            "Handler: %s, size: %d, shuffles: %d, Instructions: %d\n",
            "FifteenPuzzleHandler",
            size,
            shuffles,
            MetricsExtractor.getInstructions()
        );

        try (FileWriter fw = new FileWriter("metrics/metrics.txt", true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw)) {
            out.print(line);
        } catch (IOException e) {
            System.err.println("Error writing metrics: " + e.getMessage());
        }
    }

}
