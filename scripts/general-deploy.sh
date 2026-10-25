#!/bin/bash

# Syntax:  ./general-deploy.sh [--use-existing-image]
# By default, builds a new worker image and points AutoScaler.java to it.
# With --use-existing-image, keeps the image already set in AutoScaler.java.
# Either way, AutoScaler.java gets the key pair and security group from config.sh.

if ! source config.sh; then
	echo "Error: config.sh failed to load. Replace every <...> placeholder with your values."
	exit 1
fi
if [ ! -f "$AWS_EC2_SSH_KEYPAR_PATH" ]; then
	echo "Error: AWS_EC2_SSH_KEYPAR_PATH in config.sh points to $AWS_EC2_SSH_KEYPAR_PATH, which doesn't exist."
	exit 1
fi

AUTOSCALER=$DIR/../loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java

# Sets a String constant in AutoScaler.java, e.g. set_autoscaler_field AMI_ID ami-123.
set_autoscaler_field() {
	sed -i "s/\($1 = \"\)[^\"]*\"/\1$2\"/" $AUTOSCALER
	if ! grep -q "$1 = \"$2\"" $AUTOSCALER; then
		echo "Error: could not set $1 in AutoScaler.java."
		exit 1
	fi
	echo "AutoScaler.java: $1 = $2"
}

if [[ ! "$AWS_SECURITY_GROUP" =~ ^sg-[0-9a-f]+$ ]]; then
	echo "Error: AWS_SECURITY_GROUP in config.sh must be a security group ID (sg-...), not a name."
	exit 1
fi

if [ "$1" == "--use-existing-image" ]; then
	AMI_ID=$(sed -n 's/.*AMI_ID = "\([^"]*\)".*/\1/p' $AUTOSCALER)
	echo "Note: workers will be launched from the image set in AutoScaler.java (AMI_ID = $AMI_ID)."
	if [ -f $DIR/image.id ] && [ "$(cat $DIR/image.id)" != "$AMI_ID" ]; then
		echo "Warning: image.id contains $(cat $DIR/image.id), which differs from AMI_ID."
	fi
	sleep 5
else
	# Build a new worker image (create-image.sh writes its id to image.id).
	rm -f $DIR/image.id
	$DIR/create-image.sh

	AMI_ID=$(cat $DIR/image.id 2>/dev/null)
	if [[ ! "$AMI_ID" =~ ^ami-[0-9a-f]+$ ]]; then
		echo "Error: no new image id in image.id. If an image named CNV-Image already exists, deregister it and try again."
		exit 1
	fi
	set_autoscaler_field AMI_ID $AMI_ID
fi

# Point the auto-scaler to your key pair and security group; launch-lb.sh recompiles the load balancer.
set_autoscaler_field KEY_NAME $AWS_KEYPAIR_NAME
set_autoscaler_field SEC_GROUP_ID $AWS_SECURITY_GROUP

sleep 5

$DIR/function-register.sh

sleep 5

$DIR/launch-lb.sh
