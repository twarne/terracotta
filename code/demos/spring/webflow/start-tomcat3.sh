#!/bin/sh

#@COPYRIGHT@

TOPDIR=`dirname "$0"`/../..
. "${TOPDIR}"/libexec/tc-functions.sh

TC_JAVA_OPTS="`./"${TOPDIR}"/bin/dso-env.sh tc-config.xml`"
if [ $? -ne 0 ]; then
  echo $TC_JAVA_OPTS
  exit 1
fi

tc_install_dir "${TOPDIR}"/.. true
JAVA_OPTS="${TC_JAVA_OPTS} -Dcom.sun.management.jmxremote"
JAVA_OPTS="${JAVA_OPTS} -Dcom.sun.management.jmxremote.port=8093"
JAVA_OPTS="${JAVA_OPTS} -Dcom.sun.management.jmxremote.authenticate=false"
JAVA_OPTS="${JAVA_OPTS} -Dcom.sun.management.jmxremote.ssl=false"

JAVA_OPTS="${JAVA_OPTS} -Dtc.node-name=Node3

export JAVA_OPTS

JAVA_HOME="${TC_JAVA_HOME:-${TOPDIR}/jre}"
CATALINA_HOME="${TOPDIR}/../vendors/tomcat5.5"
CATALINA_BASE="tomcat3"
export JAVA_HOME CATALINA_HOME CATALINA_BASE

mkdir -p "${CATALINA_BASE}/logs" "${CATALINA_BASE}/temp"
exec "${CATALINA_HOME}/bin/catalina.sh" run
