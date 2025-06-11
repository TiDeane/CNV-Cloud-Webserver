package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class LambdaInstance {

    private static AWSLambda awsLambda;

    private static final LambdaInstance instance = new LambdaInstance();

    private LambdaInstance() {
        awsLambda = AWSLambdaClientBuilder.standard()
                .withCredentials(new EnvironmentVariableCredentialsProvider())
                .withRegion(Constants.AWS_REGION)
                .build();
    }

    public static LambdaInstance getInstance() {
        return instance;
    }
    
    public String lambdaRequest(String jsonRequest, String name) {
        String lambdaName = String.format(name + "-lambda");
        InvokeRequest request = new InvokeRequest().withFunctionName(lambdaName).withLogType("Tail").withPayload(ByteBuffer.wrap(jsonRequest.getBytes(StandardCharsets.UTF_8)));
        InvokeResult res = awsLambda.invoke(request);
        return new String(res.getPayload().array(), StandardCharsets.UTF_8);
    }
}
