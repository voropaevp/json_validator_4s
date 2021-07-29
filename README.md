JSON Validator Service
=

* Uses file base storage without caching
* Environment or System variable based config
  * HOSTNAME - listen hostname
  * PORT - listen port 
  * SCHEMA_DIR - json schema storage directory
    
####Run from sbt

```
C:\Users\vorop\IdeaProjects\json_validator_4s>sbt
[info] welcome to sbt 1.5.5 (Oracle Corporation Java 11.0.11)
[info] loading global plugins from C:\Users\vorop\.sbt\1.0\plugins
[info] loading project definition from C:\Users\vorop\IdeaProjects\json_validator_4s\project
[info] loading settings for project json_validator_4s from build.sbt ...
[info] set current project to json_validator_4s (in build file:/C:/Users/vorop/IdeaProjects/json_validator_4s/)
[info] sbt server started at local:sbt-server-717b29bcf3babe6bccb5
[info] started sbt server
sbt:json_validator_4s> run
[info] running Main
21:40:25.889 [io-compute-12] INFO org.http4s.blaze.channel.nio1.NIO1SocketServerGroup - Service bound to address /127.0.0.1:8881
21:40:25.894 [blaze-acceptor-0-0] DEBUG org.http4s.blaze.channel.nio1.SelectorLoop - Channel initialized.
21:40:25.897 [io-compute-12] INFO org.http4s.blaze.server.BlazeServerBuilder -
  _   _   _        _ _
 | |_| |_| |_ _ __| | | ___
 | ' \  _|  _| '_ \_  _(_-<
 |_||_\__|\__| .__/ |_|/__/
             |_|
21:40:25.912 [io-compute-12] INFO org.http4s.blaze.server.BlazeServerBuilder - http4s v0.15.1 on blaze v0.15.1 started at http://127.0.0.1:8881/
21:40:51.547 [blaze-selector-0] DEBUG org.http4s.blaze.channel.nio1.SelectorLoop - Channel initialized.
21:40:51.548 [blaze-selector-0] DEBUG org.http4s.blaze.channel.nio1.NIO1HeadStage - Starting up.

```

#### Valid JSON Schema Upload

This should contain Schema id, action and status.

```json
{
    "action": "uploadSchema",
    "id": "config-schema",
    "status": "success"
}
```

#### Invalid JSON Schema Upload

It isn't necessary to check whether the uploaded JSON is a valid JSON Schema v4 (many validation libraries dont allow it),
but it is required to check whether the document is valid JSON.

```json
{
    "action": "uploadSchema",
    "id": "config-schema",
    "status": "error",
    "message": "Invalid JSON"
}
```

#### JSON document was successfully validated

```json
{
    "action": "validateDocument",
    "id": "config-schema",
    "status": "success"
}
```

#### JSON document is invalid against JSON Schema

The returned message should contain a human-readable string or machine-readable JSON document indicating the error encountered.
The exact format can be chosen based on the validator library's features.

```json
{
    "action": "validateDocument",
    "id": "config-schema",
    "status": "error",
    "message": "Property '/root/timeout' is required"
}
``` 

