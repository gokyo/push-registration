push-registration
=============================================

[![Build Status](https://travis-ci.org/hmrc/push-registration.svg?branch=master)](https://travis-ci.org/hmrc/push-registration) [ ![Download](https://api.bintray.com/packages/hmrc/releases/push-registration/images/download.svg) ](https://bintray.com/hmrc/releases/personal-income/_latestVersion)

Register mobile device Id and Token

Requirements
------------

The following services are exposed from the micro-service.

Please note it is mandatory to supply an Accept HTTP header to all below services with the value ```application/vnd.hmrc.1.0+json```. 

API
---

| *Task* | *Supported Methods* | *Description* |
|--------|----|----|
| ```/push/registration``` | POST | Return 201 created or 200 updated. [More...](docs/registration.md)  |
| ```/push/registration/:id``` | GET | Find record by auth id. [More...](docs/find.md)  |


# Sandbox
All the above endpoints are accessible on sandbox with `/sandbox` prefix on each endpoint,e.g.
```
    POST /push/registration
```

# Definition
API definition for the service will be available under `/api/definition` endpoint.
See definition in `/conf/api-definition.json` for the format.

# Version
Version of API need to be provided in `Accept` request header
```
Accept: application/vnd.hmrc.v1.0+json
```
