package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.FunctionCode;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.model.CreateFunctionResult;
import com.amazonaws.services.lambda.model.DeleteFunctionRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LambdaInstance {
    //private static final String AWS_REGION = "eu-west-1";
    private static final String AWS_ACCOUNT_ID = "468907522103";
    private static final String HANDLER_BASE_PATH = "pt.ulisboa.tecnico.cnv.";
    private static final Integer TIMEOUT = 15;
    private static final Integer MEMORY_SIZE = 256;
    private static final String LAMBDA_ROLE = "arn:aws:iam::" + AWS_ACCOUNT_ID + ":role/lambda-role";

    private static AWSLambda awsLambda;

    public LambdaInstance() {
        awsLambda = AWSLambdaClientBuilder.standard()
                .withCredentials(new EnvironmentVariableCredentialsProvider())
                .withRegion(Constants.AWS_REGION)
                .build();
    }
    

    public void registerLambdaFunction(String jarPath, String handlerP, String name) {
        try {
            String lambdaName = String.format(name + "-lambda");
            
            byte[] functionJar = Files.readAllBytes(Paths.get(jarPath));
            FunctionCode code = new FunctionCode()
                .withZipFile(java.nio.ByteBuffer.wrap(functionJar));

            CreateFunctionRequest request = new CreateFunctionRequest()
                .withFunctionName(lambdaName)
                .withRuntime(com.amazonaws.services.lambda.model.Runtime.Java11)
                .withRole(LAMBDA_ROLE)
                .withHandler(HANDLER_BASE_PATH + handlerP)
                .withCode(code)
                .withDescription("My Lambda for " + lambdaName)
                .withTimeout(TIMEOUT)
                .withMemorySize(MEMORY_SIZE);

            CreateFunctionResult result = awsLambda.createFunction(request);
            System.out.println("Lambda ARN: " + result.getFunctionArn() + "deployed");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public String lambdaRequest(String jsonRequest, String name) {
        String lambdaName = String.format(name + "-lambda");
        InvokeRequest request = new InvokeRequest().withFunctionName(lambdaName).withLogType("Tail").withPayload(ByteBuffer.wrap(jsonRequest.getBytes(StandardCharsets.UTF_8)));
        InvokeResult res = awsLambda.invoke(request);
        return new String(res.getPayload().array(), StandardCharsets.UTF_8);
    }

    public static void deregisterLambdaFunctions() {
        DeleteFunctionRequest deleteFunctionRequest1 = new DeleteFunctionRequest()
            .withFunctionName(Constants.GOL_HANDLER + "-lambda");
        DeleteFunctionRequest deleteFunctionRequest2 = new DeleteFunctionRequest()
            .withFunctionName(Constants.FP_HANDLER + "-lambda");
        DeleteFunctionRequest deleteFunctionRequest3 = new DeleteFunctionRequest()
            .withFunctionName(Constants.CTF_HANDLER + "-lambda");

        try {
            awsLambda.deleteFunction(deleteFunctionRequest1);
            System.out.println("Lambda function for " + Constants.GOL_HANDLER + " has been deleted.");
        } catch (Exception e) {
            System.err.println("Error while deleting lambda for " + Constants.GOL_HANDLER);
            e.printStackTrace();
        }

        try {
            awsLambda.deleteFunction(deleteFunctionRequest2);
            System.out.println("Lambda function for " + Constants.FP_HANDLER + " has been deleted.");
        } catch (Exception e) {
            System.err.println("Error while deleting lambda for " + Constants.FP_HANDLER);
            e.printStackTrace();
        }

        try {
            awsLambda.deleteFunction(deleteFunctionRequest3);
            System.out.println("Lambda function for " + Constants.CTF_HANDLER + " has been deleted.");
        } catch (Exception e) {
            System.err.println("Error while deleting lambda for " + Constants.CTF_HANDLER);
            e.printStackTrace();
        }        
    }
}
