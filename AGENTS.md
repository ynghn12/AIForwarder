# AITriggerForwarder — Agent Log

## 2025-12-17
- Updated `src/main/resources/log4j2.xml` to add `RollingFile` appender writing to `logs/forwarder.log` with daily + 20MB rollovers (keep 14), while still logging to console. (Commit: Add rolling file appender for logs/forwarder.log)
- Windows run note: recommend `java "-Dconfig.file=application.conf" "-Dlog4j.configurationFile=log4j2.xml" -jar aitriggerforwarder-1.0-SNAPSHOT.jar` to avoid PowerShell argument parsing issues; ensure `logs/` directory exists for file logging.
- Network note for interface test: AI→Forwarder needs inbound TCP 50050 on Forwarder host; end-to-end success also requires Forwarder outbound to Mongo `12.93.14.33:50042` and EARS `12.93.14.33:50007`.

## 2025-11-27
- Updated `pom.xml` to internal-approved stack: Scala 2.12.18, Akka 2.6.20 / Akka HTTP 10.1.15, Log4j 2.17.1, scala-maven-plugin 4.8.1, assembly 3.3.0 (appendAssemblyId=false), shade 3.5.0. Added mongo-scala-driver 2.9.0. (Commit: Replace pom.xml with internal-approved dependency set)
- Implemented Mongo EQP_INFO lookup using `eqpId` (auth: ars/ars2015!, authSource EARS) and mapped fields to EARS headers (process/process, eqpModel/model, lineDesc/line). Added config block in `application.conf`; Mongo repo injected into routes; shutdown hook closes client. (Commit: Add Mongo EQP_INFO lookup with config and driver 2.9.0)
- Adjusted EARS headers: `name` from scname, `txn` forwarded, `triggerBy=AI`. (Commit: Adjust EARS headers: name from scname, txn, triggerBy=AI)
- Enhanced logging: INFO log of EQP_INFO lookup result (txn, eqpid, process, model, ip). `EqpInfo` now holds optional ip. (Commit: Log EQP_INFO details and include ip in lookup mapping)

## 2025-11-24
- Initialized Maven Scala 2.12.11 / Akka HTTP 10.1.12 scaffold with `/ai_trigger`, validation, txn generator, stub Mongo lookup, EARS client (5s timeout), success/500 handling.
- Added baseline config (`application.conf`) and log4j2 console logging.
