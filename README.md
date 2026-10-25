## Games@Cloud

This project consists of a load balancer/autoscaler instance that launches and manages webservers, instrumented with Javassist, that run computationally-intensive tasks based on games, namely: capturetheflag, fifteenpuzzle and gameoflife. To balance load efficiently, it uses two DynamoDB tables to estimate request cost and measure current load, and sends low complexity requests to Lambda.

Detailed information on this project, including reasoning for the decisions made, is available in the [report](final_project_report_CNV_g28.pdf), which explains:
- The **architecture**;
- **Instrumentation metrics**;
- **Data structures** (DynamoDB tables);
- **Request cost estimation**;
- **Task scheduling algorithm** (load balancing);
- **Auto-scaling algorithm**;
- **Fault-tolerance**.

The report describes the project as delivered. Later changes are listed in [Changes after delivery](#changes-after-delivery).

## Modules

This project contains eight modules:

1. `capturetheflag` - the Capture the Flag workload
2. `fifteenpuzzle` - the 15-Puzzle Solver workload
3. `gameoflife` - the Conway's Game of Life workload
4. `webserver` - the web server exposing the functionality of the workloads
5. `javassist` - the Javassist agent used to extract metrics about executed requests
6. `common` - request cost estimation and request lifecycle management
7. `dynamodbclient` - manages the deployment of and interactions our DynamoDB
8. `loadbalancer` - the loadbalancer and autoscaler

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`
3. To build and deploy the entire project from scratch, including the AMI image, run the script ``general-deploy.sh``. More information on what specifically this script does is in the next section.

For a step-by-step guide to deploying on AWS and testing the web UI against the deployment, including setup, known traps and teardown, see [test.md](test.md).

### Project Scripts

As mentioned in the previous section, the script ``general-deploy.sh`` can be used to deploy the entire project from scratch. It checks ``config.sh``, then:
- Runs ``create-image.sh``, which creates an AMI of the webserver.
- Writes the new AMI ID, and the key pair and security group from ``config.sh``, into ``AutoScaler.java``.
- Runs ``function-register.sh``, which registers our lambda functions in AWS.
- Runs ``launch-lb.sh``, which compiles and launches our own custom loadbalancer/autoscaler in an EC2 instance.

With ``--use-existing-image``, it skips creating the AMI and keeps the one already set in ``AutoScaler.java``.

The script ``terminate-deploy.sh`` tears the deployment down: the load balancer, the workers, the lambda functions and the AMI with its snapshot. It asks whether to also delete the DynamoDB tables, and ``--keep-image`` keeps the AMI for a later ``general-deploy.sh --use-existing-image``.

**Note**: to run these scripts, it is necessary to write your AWS credentials, region, key pair and security group into the script ``config.sh``. The Java code also reads the region from it (``AWS_DEFAULT_REGION``).

**Running locally**: to run our load balancer or webservers locally, it is also necessary to load your AWS credentials and region into the environment with ``source config.sh`` so that they can interact with AWS resources.

**Full AWS deployment**: The script ``launch-deployment-template.sh`` can also be used to launch the deployment using alarms, load balancing and auto scaling with AWS-owned resources.

**Generating load**: in the directory ``scripts/tests/``, there are multiple scripts that send requests of different types to generate load on the webservers. [test.md](test.md) shows how to use them.

## Web UI

The directory ``webui/`` contains a browser console for sending requests, visualizing each game and running load bursts. It also includes a mock load balancer, so the UI can be tried without AWS or Java. See [webui/README.md](webui/README.md), and [test.md](test.md) for using it against the real AWS deployment.

## Changes after delivery

The project was delivered, discussed and graded before the changes below were made. None of them are crucial or load-bearing: the system worked as delivered, and they don't change its architecture or the algorithms described in the report.

- The auto-scaling CPU thresholds were adjusted, following the professors' advice at the project discussion.
- The [web UI](#web-ui) was added, along with the ``X-Handled-By`` and ``X-Request-Id`` response headers in the load balancer.
- The deployment scripts were improved and automated.
- The Java code reads the AWS region from ``config.sh`` instead of hardcoding ``eu-west-1``.
- The root handlers (``/``) now finish their responses instead of leaving them open.
- A [deployment guide](test.md) was added.
