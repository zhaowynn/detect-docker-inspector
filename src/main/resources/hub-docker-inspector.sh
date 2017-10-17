#!/bin/bash
#
# This script runs on the host machine, and uses hub-docker-inspector images/containers
# to inspect the given Docker image.
#
# Run this script from the directory that contains the application.properties, configured
# with your Hub connection details (hub.url, hub.username, and hub.password),
# and Docker Hub connection details (docker.registry.username and docker.registry.password).

# To override the default location of /tmp, specify
# your own DOCKER_INSPECTOR_TEMP_DIR in your environment and
# *that* location will be used.
DOCKER_INSPECTOR_TEMP_DIR=${DOCKER_INSPECTOR_TEMP_DIR:-/tmp/hub-docker-inspector}
echo "******* DOCKER_INSPECTOR_TEMP_DIR: ${DOCKER_INSPECTOR_TEMP_DIR}"

# If you want to pass any additional options to
# curl, specify DOCKER_INSPECTOR_CURL_OPTS in your environment.
# For example, to specify a proxy, you would set
# DOCKER_INSPECTOR_CURL_OPTS=--proxy http://myproxy:3128
DOCKER_INSPECTOR_CURL_OPTS=${DOCKER_INSPECTOR_CURL_OPTS:-}


function printUsage() {
	echo ""
    echo "Usage: $0 [options]"
    echo "options: any property from application.properties can be set by adding an option of the form:"
    echo "  --<property name>=<value>"
    echo ""
    echo "Run this command from the directory that contains the application.properties,"
    echo "configured with your Hub connection details (hub.url, hub.username, and hub.password),"
	echo "and Docker Hub connection details (docker.registry.username and docker.registry.password)."
	echo ""
	echo "For greater security, the Hub password can be set via the environment variable BD_HUB_PASSWORD"
	echo ""
	echo "For example:"
	echo "  export BD_HUB_PASSWORD=mypassword"
	echo "  $0 --hub.url=http://hub.mydomain.com:8080/ --hub.username=myusername ubuntu"
	echo ""
	echo "Documentation: https://blackducksoftware.atlassian.net/wiki/spaces/INTDOCS/pages/48435867/Hub+Docker+Inspector"
}

# Write message to stdout
log() {
  echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')]: $@"
}

# Write warning to stdout
warn() {
  echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')]: WARNING: $@"
}

# Write error message to stderr
err() {
  echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')]: ERROR: $@" >&2
}

function getLatestVersion() {
	VERSION_FILE_DESTINATION="${DOCKER_INSPECTOR_TEMP_DIR}/hub-docker-inspector-latest-commit-id.txt"
	CURRENT_VERSION=""
	if [ -f $VERSION_FILE_DESTINATION ]; then
		CURRENT_VERSION=$( <$VERSION_FILE_DESTINATION )
	fi

	curl $DOCKER_INSPECTOR_CURL_OPTS -o $VERSION_FILE_DESTINATION https://blackducksoftware.github.io/hub-docker-inspector/latest-commit-id.txt
	LATEST_VERSION=$( <$VERSION_FILE_DESTINATION )
	echo "The latest version of the hub-docker-inspector jar file: ${LATEST_VERSION}"
	echo "The currently-installed version of the hub-docker-inspector jar file: ${CURRENT_VERSION}"
}

# Expand tilde
function expandPath() {
	echo "${@/#~/$HOME}"
}

# escape spaces
function escapeSpaces() {
	echo "${@// /%20}"
}

# Look through args for ones this script needs to act on
function preProcessOptions() {
	cmdlineargindex=0
	for cmdlinearg in "$@"
	do
		if [[ "$cmdlinearg" == --jar.path=* ]]
		then
			jarPath=$(echo "$cmdlinearg" | cut -d '=' -f 2)
			jarPath=$(expandPath "${jarPath}")
			jarPathEscaped=$(escapeSpaces "${jarPath}")
			options[${cmdlineargindex}]="--jar.path=${jarPathEscaped}"
			jarPathAlreadySet=true
		elif [[ "$cmdlinearg" == --spring.config.location=* ]]
		then
			# Once IDETECT-339 is done/released, this clause can go away
			springConfigLocation=$(echo "$cmdlinearg" | cut -d '=' -f 2)
			if ! [[ "$springConfigLocation" == file:* ]]
			then
				springConfigLocation="file:${springConfigLocation}"
			fi
			if ! [[ "$springConfigLocation" == */application.properties ]]
			then
				if [[ "$springConfigLocation" == */ ]]
				then
					springConfigLocation="${springConfigLocation}application.properties"
				else
					springConfigLocation="${springConfigLocation}/application.properties"
				fi
			fi
			options[${cmdlineargindex}]="--spring.config.location=${springConfigLocation}"
		else
			if [[ "${cmdlineargindex}" -eq $(( $# - 1)) ]]
			then
				if [[ "${cmdlinearg}" =~ ^--.*=.* ]]
				then
					options[${cmdlineargindex}]="${cmdlinearg}"
				else
					image="${cmdlinearg}"
					if [[ "${image}" == *.tar ]]
					then
						warn "This command line format is deprecated. Please replace the final argument ${image} with --docker.tar=${image}"
						options[${cmdlineargindex}]="--docker.tar=${cmdlinearg}"
					else
						warn "This command line format is deprecated. Please replace the final argument ${image} with --docker.image=${image}"
						options[${cmdlineargindex}]="--docker.image=${cmdlinearg}"
					fi
				fi
			else
				options[${cmdlineargindex}]="${cmdlinearg}"
			fi
		fi
		(( cmdlineargindex += 1 ))
	done
}

# Pull the latest jar down to the current working directory
function pullJar {
	log "Getting hub-docker-inspector.jar from github"
	jarUrl="https://blackducksoftware.github.io/hub-docker-inspector/hub-docker-inspector-${version}.jar"
	curl --fail -O  ${jarUrl}
	curlStatus=$?
	if [[ "${curlStatus}" != "0" ]]
	then
		err "${curlStatus} fetching ${jarUrl}. If you have the hub-docker-inspector .jar file, you can set the jar.path property to the path to the .jar file"
		exit ${curlStatus}
	fi
}

##################
# Start script
##################
version="@VERSION@"
encodingSetting="-Dfile.encoding=UTF-8"
jarPath=""
jarPathAlreadySet=false

getLatestVersion

if [ $# -lt 1 ]
then
    printUsage
    exit -1
fi

if [ \( "$1" = -v \) -o \( "$1" = --version \) ]
then
	echo "$(basename $0) ${version}"
	exit 0
fi

if [ \( "$1" = -h \) -o \( "$1" = --help \) ]
then
    printUsage
    exit 0
fi

if [ \( "$1" = -j \) -o \( "$1" = --pulljar \) ]
then
    pullJar
    exit 0
fi

preProcessOptions "$@"

if [ -z "${jarPath}" ]
then
	pushd "${DOCKER_INSPECTOR_TEMP_DIR}" > /dev/null
	pullJar
	popd > /dev/null
	jarPath="${DOCKER_INSPECTOR_TEMP_DIR}/hub-docker-inspector-${version}.jar"
fi
log "Jar path: ${jarPath}"

newJarPathAssignment=""
if [[ $jarPathAlreadySet == false ]]
then
	newJarPathAssignment="--jar.path=${jarPath}"
fi

log "jarPath: ${jarPath}"
log "Options: ${options[*]}"
java "${encodingSetting}" ${DOCKER_INSPECTOR_JAVA_OPTS} -jar "${jarPath}" "${newJarPathAssignment}" ${options[*]}
status=$?

exit ${status}
