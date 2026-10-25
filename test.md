# Deploying Games@Cloud and testing the web UI against it

Steps to launch the real AWS deployment (custom load balancer, EC2 workers, Lambdas, DynamoDB tables) and use the web UI against it. Run every script from inside `scripts/`, because they load `config.sh` from the current directory. Before running `aws` commands by hand, run `source config.sh` in that shell too, so they use the right credentials and region.

## Traps in the scripts

- **Your AWS keys get copied onto the machines.** `config.sh` holds your credentials, and the scripts copy it onto the worker image and the load balancer. Keep the image private and delete it when you're done.
- **The Lambda runtime and base image are old.** The scripts use Lambda's `java11` runtime and an Amazon Linux 2 image. AWS still accepted both in October 2026. If it rejects them later, use `--runtime java17` or `java21` (the jars still run there) and an Amazon Linux 2023 image. This switch is untested.

## 1. One-time setup

1. Install the tools the scripts use: AWS CLI, JDK 11+, Maven, `jq`, `nc`, `ssh` and `scp`. The load tests in `scripts/tests/` also need `ab` (in the `apache2-utils` package on Debian and Ubuntu).
2. Fill in `scripts/config.sh`: the AWS CLI path, region, account ID, access keys, key-pair name and `.pem` path, and the security group ID. The key pair and security group must be in the region you set. The scripts and the Java code (load balancer, auto-scaler, workers and DynamoDB client) all use `AWS_DEFAULT_REGION`, falling back to `eu-west-1` when it isn't set.
3. Open these inbound ports in the security group:

| Port | Needed for |
|---|---|
| 22 | The scripts' SSH and SCP |
| 8000 | The load balancer reaching workers, plus `test-vm.sh` from your machine |
| 8001 | Your browser reaching the load balancer |

The load balancer reaches workers at their public IP addresses, so 8000 must be open to it, not only inside the VPC. Without 8001, requests to the load balancer time out.

## 2. Deploy

Deploy with a single script (option A), or run its steps yourself (option B).

### Option A: `general-deploy.sh`

```bash
cd scripts
./general-deploy.sh
```

This does the following:

1. Checks `config.sh`: that it loads without errors (no `<...>` placeholders left), that the `.pem` file exists, and that `AWS_SECURITY_GROUP` is an `sg-…` ID.
2. Runs `create-image.sh` to build a new worker image.
3. Writes the new image's ID into `AMI_ID` in `AutoScaler.java`, and your key pair and security group from `config.sh` into `KEY_NAME` and `SEC_GROUP_ID`.
4. Runs `function-register.sh` to create the Lambdas.
5. Runs `launch-lb.sh`, which recompiles the load balancer with those values and deploys it.

The script stops before changing `AutoScaler.java` if no new image was created, for example because an image named `CNV-Image` already exists. Deregister the old image and run it again.

To reuse the image already set in `AutoScaler.java`, run `./general-deploy.sh --use-existing-image`. It skips building an image, prints that image's ID, and warns if it differs from `image.id`. It still sets `KEY_NAME` and `SEC_GROUP_ID`.

The script edits `AutoScaler.java`, a tracked file, so your values show up in `git status`. Leave that change out of your commits.

### Option B: step by step

1. **Build the worker image:**

   ```bash
   cd scripts
   ./create-image.sh
   ```

   This builds everything, launches a temporary instance, installs the worker on it, and tests it with a curl request (you should see a Game of Life JSON reply). It then saves the instance as an image called `CNV-Image`, writes the image ID to `scripts/image.id`, and deletes the temporary instance. If an image with that name already exists, deregister it first.

2. **Configure the auto-scaler:** in `loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java` (around lines 56-59), set:
   - `AMI_ID` = the contents of `scripts/image.id`
   - `KEY_NAME` = your key-pair name
   - `SEC_GROUP_ID` = your security group ID

3. **Create the Lambdas and the load balancer:**

   ```bash
   ./function-register.sh   # IAM role + 3 Lambda functions
   ./function-invoke.sh     # optional: each Lambda should print a game result
   ./launch-lb.sh           # rebuilds (with the edited AutoScaler), installs, reboots the LB
   ```

### What to expect (both options)

- **On a rerun:** if `lambda-role` exists from an earlier run, `function-register.sh` prints `EntityAlreadyExists` for it and carries on. That's harmless. The functions show `"State": "Pending"` when created, and become `Active` a few seconds later.
- **The address:** `launch-lb.sh` writes the load balancer's address to `scripts/loadbalancer.dns`.
- **Start-up:** after the reboot, the load balancer starts itself and the auto-scaler launches the first worker. Expect a few minutes before EC2-bound requests are served; until then the load balancer holds them.

To check the load balancer without the UI, use the scripts in `scripts/tests/`. Each takes the host, the port and the game's parameters:

```bash
LB=$(cat loadbalancer.dns)
./tests/testgameoflife-simple.sh $LB 8001 glider-10-10.json 10
./tests/testfifteenpuzzle-simple.sh $LB 8001 4 20
./tests/testcapturetheflag-simple.sh $LB 8001 20 5 5 A
```

The `-simple` scripts send one request and print the result. The `-load` scripts take the same arguments. They send one request, then four more one after another with ApacheBench (`ab`), and print its timing summary.

The scripts don't show response headers. To see which backend served a request, use `curl -i` and look for `X-Handled-By`:

```bash
curl -i "http://$LB:8001/gameoflife?mapFilename=glider-10-10.json&iterations=10"
```

## 3. Test the UI against it

```bash
cd ..
python3 -m http.server -d webui 8080
```

Open `http://localhost:8080`, set Target to `http://<contents of scripts/loadbalancer.dns>:8001`, and click **Check**. The dot should turn green. Check failed in Firefox but works in Chrome, so if it stays red while curl works, try another browser. Then try:

1. **Light request**, e.g. Game of Life on `glider-10-10.json` with 10 iterations. It should show **Lambda**, which confirms the Lambda decoding and the new header.
2. **Heavy request**, e.g. Capture the Flag with grid size 100, 30 agents per team, placement A, or Game of Life on `glider-10-10.json` with 200000 iterations. Both were served by **EC2 i-…** in testing. The first one may wait while the worker boots.
3. **Burst test**: 20 light requests at concurrency 5 should be mostly Lambda.
   - A sustained heavy burst can trigger a scale-up (for example, 6 heavy requests at concurrency 3). The auto-scaler checks every 30 seconds, scales on average CPU above 70% over 5 minutes, waits 5 minutes between scaling events, and is capped at 2 workers.
   - Lambdas time out after 10 seconds. A request that was predicted to be light but runs longer is retried three times and then sent to EC2.

Where a request goes depends on its estimated cost. The load balancer uses the stored metrics if `CNVRequestMetrics` has an entry with the same arguments, and the cost estimator otherwise. The tables survive teardown unless you delete them, so routing can differ between deployments. A request near the threshold can go to Lambda once and to EC2 the next time.

`CNVRequestMetrics` only gets entries for requests served by EC2. The metrics come from the Javassist agent, which only runs on the workers, so Lambda requests are never recorded.

## 4. Tear down (it costs money while running)

### Option A: `terminate-deploy.sh`

```bash
cd scripts
./terminate-deploy.sh
```

It first asks whether to also delete the `CNVRequestMetrics` and `CNVOngoingRequests` tables. The default answer is no, because the tables hold the cost estimator's data. It then lists what it found and does the following, without asking again:

1. Terminates the load balancer, so the auto-scaler can't launch new workers.
2. Terminates any workers still alive (instances launched from the image in `image.id` or `AMI_ID`), plus the temporary instance from `create-image.sh` if it's still there.
3. Deletes the three Lambdas and `lambda-role` (through `function-deregister.sh`).
4. Deregisters the image and deletes its snapshot. It only does this if `image.id` names an image of yours called `CNV-Image`.
5. Deletes the tables, if you said yes.
6. Checks that no instance is left, and deletes the `.id` and `.dns` files in `scripts/`.

To keep the image (and `image.id`) so you can redeploy with `./general-deploy.sh --use-existing-image`, run `./terminate-deploy.sh --keep-image`.

### Option B: by hand

```bash
cd scripts
source config.sh
aws ec2 terminate-instances --instance-ids $(cat loadbalancer.id)
./function-deregister.sh
SNAPSHOT=$(aws ec2 describe-images --image-ids $(cat image.id) --query 'Images[0].BlockDeviceMappings[0].Ebs.SnapshotId' --output text)
aws ec2 deregister-image --image-id $(cat image.id)
aws ec2 delete-snapshot --snapshot-id $SNAPSHOT
```

- **Workers:** the load balancer's shutdown hook should terminate the workers it started. Check in the EC2 console that none are left.
- **DynamoDB:** delete the `CNVRequestMetrics` and `CNVOngoingRequests` tables if you don't want to keep them.

## Alternative: AWS-managed deployment

`launch-deployment-template.sh` sets up AWS's own load balancer and auto-scaling group in front of the workers, without the custom load balancer or Lambdas. The UI works with it at `http://<ELB DNS>` (port 80), but every result will show "Worker (direct)". Its availability zone is hardcoded to `us-east-1a`, so it only works when `AWS_DEFAULT_REGION` is `us-east-1`. Tear it down with `terminate-deployment-template.sh`.
