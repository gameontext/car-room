#!/bin/bash

if [ "$ETCDCTL_ENDPOINT" != "" ]; then
  echo Setting up etcd...
  wget https://github.com/coreos/etcd/releases/download/v2.2.2/etcd-v2.2.2-linux-amd64.tar.gz -q
  tar xzf etcd-v2.2.2-linux-amd64.tar.gz etcd-v2.2.2-linux-amd64/etcdctl --strip-components=1
  rm etcd-v2.2.2-linux-amd64.tar.gz
  mv etcdctl /usr/local/bin/etcdctl
  
  export service_room=$(etcdctl get /room/service)
  
  /opt/ibm/wlp/bin/server start defaultServer
  echo Starting the logstash forwarder...
  sed -i s/PLACEHOLDER_LOGHOST/$(etcdctl get /logstash/endpoint)/g /opt/forwarder.conf
  cd /opt
  chmod +x ./forwarder
  etcdctl get /logstash/cert > logstash-forwarder.crt
  etcdctl get /logstash/key > logstash-forwarder.key
  sleep 0.5
  ./forwarder --config ./forwarder.conf
else
  if [ "$CONNECTION_URL" == "" ]; then
    export CONNECTION_URL=ws://127.0.0.1:9080/cars/carRoom
  fi
  if [ "$MAP_URL" == "" ]; then
    export MAP_URL=http://127.0.0.1:9080/map/v1/sites
  fi
  if [ "$OWNER_ID" == "" ]; then
    export OWNER_ID=dummy.DevUser
  fi
  if [ "$OWNER_KEY" == "" ]; then
    export OWNER_KEY=TODO_CHANGE_ME
  fi
  if [ "$CAR_URL" == "" ]; then
    export CAR_URL=ws://127.0.0.1:9080/LibertyCar/control
  fi
  echo "CAR_URL=$CAR_URL"
  if [ "$REQUIRES_APP_REGISTRATION" == "" ]; then
    export REQUIRES_APP_REGISTRATION=false
  fi

  /opt/ibm/wlp/bin/server run defaultServer
fi
