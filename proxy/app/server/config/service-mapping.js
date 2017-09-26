/**
 * Copyright 2017 deepsense.ai (CodiLime, Inc)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const _ = require('underscore');

var localproxybridge = "http://172.20.1.157:33321"
var localUIServer = "http://127.0.0.1:3000"

const authorization = {
    "path": "/authorization",
    "host": localproxybridge,
    "name": "sso",
    "proxyTimeout": 1000,
    "timeoutRedirectionPage": "wait.html"
};

const serviceMapping = [authorization, {
    "path": "/v1/workflows",
    "host": localproxybridge,
    "name": "workflow-manager",
    "proxyTimeout": 100000,
    "auth": "basic"
}, {
    "path": "/v1/presets",
    "host": localproxybridge,
    "name": "workflow-manager",
    "auth": "basic"
}, {
    "path": "/v1/operations",
    "host": localproxybridge,
    "name": "workflow-manager",
    "auth": "basic"
}, {
    "path": "/v1/sessions",
    "host": localproxybridge,
    "name": "session-manager"
}, {
    "path": "/datasourcemanager/v1",
    "host": localproxybridge,
    "name": "datasource-manager"
}, {
    "path": "/schedulingmanager/v1",
    "host": localproxybridge,
    "name": "scheduling-manager"
}, {
    "path": "/jupyter",
    "host": localproxybridge,
    "name": "jupyter"
}, {
    "path": "/library",
    "host": localproxybridge,
    "name": "library"
}, {
    "path": "/stomp",
    "host": localproxybridge,
    "name": "rabbitmq"
}, {
    "path": "/docs",
    "host": localproxybridge,
    "name": "documentation"
}, {
    "path": "/",
    "host": localUIServer,
    "name": "frontend"
}];

function getServiceForRequest(requestUrl) {
    return _.find(serviceMapping, function (service) {
        return requestUrl.match(service.path);
    });
}

module.exports = {
    getServiceForRequest,
    authorization
};
