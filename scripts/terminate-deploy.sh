#!/bin/bash

# Syntax:  ./terminate-deploy.sh [--keep-image]
# Tears down what general-deploy.sh creates: the load balancer, the workers it launched,
# the Lambdas and their IAM role, and the worker image with its snapshot.
# With --keep-image, keeps the image (for a later "general-deploy.sh --use-existing-image").
# Asks whether to also delete the DynamoDB tables, which hold the cost estimator's data.

if ! source config.sh; then
	echo "Error: config.sh failed to load. Replace every <...> placeholder with your values."
	exit 1
fi

KEEP_IMAGE=false
for arg in "$@"; do
	case $arg in
		--keep-image) KEEP_IMAGE=true ;;
		*) echo "Unknown option: $arg"; exit 1 ;;
	esac
done

ALIVE_STATES="pending,running,stopping,stopped"
LAMBDAS="gameoflife-lambda fifteenpuzzle-lambda capturetheflag-lambda"
TABLES="CNVRequestMetrics CNVOngoingRequests"
AUTOSCALER=$DIR/../loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java

# Prints its arguments separated by commas, or "none" if there are none.
join_or_none() {
	if [ $# -eq 0 ]; then echo none; else echo "$*" | sed 's/ /, /g'; fi
}

# Prints the ids among $@ that are still alive.
alive_instances() {
	[ $# -eq 0 ] && return
	aws ec2 describe-instances --instance-ids "$@" \
		--filters Name=instance-state-name,Values=$ALIVE_STATES \
		--query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null
}

# Prints the alive instances launched from the worker image(s).
alive_workers() {
	[ -z "$WORKER_IMAGES" ] && return
	aws ec2 describe-instances \
		--filters Name=image-id,Values=$(echo $WORKER_IMAGES | tr ' ' ',') Name=instance-state-name,Values=$ALIVE_STATES \
		--query 'Reservations[].Instances[].InstanceId' --output text
}

# Find what exists.
echo "Looking for the deployment's resources in $AWS_DEFAULT_REGION..."
echo "  Checking instances..."
LB_ID=$(alive_instances $(cat $DIR/loadbalancer.id 2>/dev/null))
TEMP_ID=$(alive_instances $(cat $DIR/instance.id 2>/dev/null))

IMAGE_ID=$(cat $DIR/image.id 2>/dev/null)
AUTOSCALER_AMI=$(sed -n 's/.*AMI_ID = "\([^"]*\)".*/\1/p' $AUTOSCALER)
WORKER_IMAGES=$(echo $IMAGE_ID $AUTOSCALER_AMI | tr ' ' '\n' | sort -u | xargs)
WORKER_IDS=$(alive_workers)

# Only deregister the image if it's the one create-image.sh made (named CNV-Image).
IMAGE_INFO=""
if [ -n "$IMAGE_ID" ] && ! $KEEP_IMAGE; then
	echo "  Checking the image..."
	IMAGE_INFO=$(aws ec2 describe-images --owners self --image-ids $IMAGE_ID \
		--query 'Images[?Name==`CNV-Image`].[ImageId, join(` `, BlockDeviceMappings[].Ebs.SnapshotId)]' --output text 2>/dev/null)
fi
SNAPSHOT_IDS=$(echo "$IMAGE_INFO" | cut -s -f2)

echo "  Checking Lambda functions and lambda-role..."
EXISTING_LAMBDAS=""
for fn in $LAMBDAS; do
	aws lambda get-function --function-name $fn >/dev/null 2>&1 && EXISTING_LAMBDAS="$EXISTING_LAMBDAS $fn"
done
ROLE_EXISTS=false
aws iam get-role --role-name lambda-role >/dev/null 2>&1 && ROLE_EXISTS=true

echo "  Checking DynamoDB tables..."
EXISTING_TABLES=""
for table in $TABLES; do
	aws dynamodb describe-table --table-name $table >/dev/null 2>&1 && EXISTING_TABLES="$EXISTING_TABLES $table"
done
DELETE_TABLES=false
if [ -n "$EXISTING_TABLES" ]; then
	read -p "Also delete the DynamoDB tables $(join_or_none $EXISTING_TABLES)? They hold the cost estimator's data. [y/N] " answer
	[[ "$answer" =~ ^[yY]$ ]] && DELETE_TABLES=true
fi
$DELETE_TABLES || EXISTING_TABLES=""

# Show the plan.
echo
echo "This will delete:"
echo "  Load balancer instance:   $(join_or_none $LB_ID)"
echo "  Worker instances:         $(join_or_none $WORKER_IDS) (launched from $(join_or_none $WORKER_IMAGES))"
echo "  Temporary image instance: $(join_or_none $TEMP_ID)"
echo "  Lambda functions:         $(join_or_none $EXISTING_LAMBDAS)"
echo "  IAM role lambda-role:     $($ROLE_EXISTS && echo yes || echo none)"
if $KEEP_IMAGE; then
	echo "  Image:                    kept (--keep-image)"
elif [ -n "$IMAGE_INFO" ]; then
	echo "  Image:                    $IMAGE_ID (snapshot $(join_or_none $SNAPSHOT_IDS))"
elif [ -n "$IMAGE_ID" ]; then
	echo "  Image:                    none ($IMAGE_ID from image.id is not a CNV-Image of yours, so it's kept)"
else
	echo "  Image:                    none (no image.id)"
fi
if $DELETE_TABLES; then
	echo "  DynamoDB tables:          $(join_or_none $EXISTING_TABLES)"
else
	echo "  DynamoDB tables:          kept"
fi

if [ -z "$LB_ID$WORKER_IDS$TEMP_ID$EXISTING_LAMBDAS$IMAGE_INFO$EXISTING_TABLES" ] && ! $ROLE_EXISTS; then
	echo "Nothing to delete."
	exit 0
fi

# Terminate the load balancer first, so the auto-scaler can't launch new workers.
# Its shutdown hook also terminates the workers it knows about.
if [ -n "$LB_ID" ]; then
	echo "Terminating load balancer $LB_ID..."
	aws ec2 terminate-instances --instance-ids $LB_ID >/dev/null
	aws ec2 wait instance-terminated --instance-ids $LB_ID
fi

# Terminate any workers (and the temporary image instance) still alive.
REMAINING=$(echo $(alive_workers) $TEMP_ID)
if [ -n "$REMAINING" ]; then
	echo "Terminating instances $(join_or_none $REMAINING)..."
	aws ec2 terminate-instances --instance-ids $REMAINING >/dev/null
	aws ec2 wait instance-terminated --instance-ids $REMAINING
fi

if [ -n "$EXISTING_LAMBDAS" ] || $ROLE_EXISTS; then
	echo "Deleting Lambda functions and lambda-role..."
	$DIR/function-deregister.sh 2>/dev/null
fi

if [ -n "$IMAGE_INFO" ]; then
	echo "Deregistering image $IMAGE_ID..."
	aws ec2 deregister-image --image-id $IMAGE_ID
	for snap in $SNAPSHOT_IDS; do
		echo "Deleting snapshot $snap..."
		aws ec2 delete-snapshot --snapshot-id $snap
	done
fi

for table in $EXISTING_TABLES; do
	echo "Deleting DynamoDB table $table..."
	aws dynamodb delete-table --table-name $table >/dev/null
done

# Check that nothing is left running.
LEFT=$(echo $(alive_instances $LB_ID $TEMP_ID) $(alive_workers))
if [ -n "$LEFT" ]; then
	echo "Warning: these instances are still alive: $LEFT. Check the EC2 console."
	exit 1
fi

rm -f $DIR/loadbalancer.id $DIR/loadbalancer.dns $DIR/instance.id $DIR/instance.dns
[ -n "$IMAGE_INFO" ] && rm -f $DIR/image.id
echo "Teardown complete."
