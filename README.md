[![CircleCI](https://circleci.com/gh/mavenlink/changestream.svg?style=svg)](https://circleci.com/gh/mavenlink/changestream)

<img src="https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRTURAvcnXp2Fj7Kx83GStK-PbSfOw1rFfxtygMsayJ2I9FUYR-Pg" align="right" />

# Changestream
Changestream sources object-level change events from a [MySQL Replication Master](http://dev.mysql.com/doc/refman/5.7/en/replication-howto-masterbaseconfig.html) (configured for [row-based replication](http://dev.mysql.com/doc/refman/5.7/en/replication-rbr-usage.html)) by streaming the [MySQL binlog](https://dev.mysql.com/doc/internals/en/binary-log.html) and transforming change events into JSON strings that can be published anywhere.

Currently, [Amazon Simple Queuing Service (SQS)](https://aws.amazon.com/sqs/) and [Amazon Simple Notification Service](https://aws.amazon.com/sns/) are supported with optional message encryption via AES.

## Why SNS+SQS?
For anyone who is unfamilar with the technology, [Fabrizio Branca](https://github.com/fbrnc) has some great reading on [the differences between SNS, SQS, and Kenesis](http://fbrnc.net/blog/2016/03/messaging-on-aws).

SNS+SQS is a reliable, simple, and massively scalable distributed message bus for large applications due to its [straightforward API](http://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/Welcome.html), [automatic visibility timeout](http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/MessageLifecycle.html), [rapid autoscaling](http://docs.aws.amazon.com/autoscaling/latest/userguide/as-using-sqs-queue.html), and [Fan-out](http://docs.aws.amazon.com/sns/latest/dg/SNS_Scenarios.html) capabilities.

SQS and/or SNS is trusted by many companies:

- [Dropbox](https://www.youtube.com/watch?v=mP46FviScYQ)
- [Unbounce](http://inside.unbounce.com/product-dev/aws-messaging-patterns/)
- [Kapost](http://engineering.kapost.com/2015/07/decoupling-ruby-applications-with-amazon-sns-sqs/)
- [Mavenlink](https://www.mavenlink.com/careers) (In Development)


## How do I Consume My Events?
There are several great options here, and I'll share a few (Ruby-based) examples that we've run across in the course of building Changestream:

- The [Circuitry](https://github.com/kapost/circuitry) gem by Kapost makes it easy to create SQS queues and configure your SNS changestream topic to fan out to them.
- [Shoryuken](https://github.com/phstc/shoryuken) is a super efficient threaded AWS SQS message processor for Ruby that is influenced heavily by [Sidekiq](https://github.com/mperham/sidekiq).

### changestream-circuitry-example
For your convenience, I've posted an example application that uses Circuitry to consume change events from Changestream: [mavenlink/changestream-circuitry-example](https://github.com/mavenlink/changestream-circuitry-example) (based on [kapost/circuitry-example](https://github.com/kapost/circuitry-example))

## Getting Started
### The Easy Way with Docker
The easiest way to get started with Changestream is to simply run:
```
$ docker-compose up
```

After running `docker-compose up`, you will have access to a mysql instance on localhost port 3306 with a root password of "password", and changestream configured to listen for events.

*Changestream is available on [Docker Hub](https://hub.docker.com/r/mavenlink/changestream/).*

### The Bespoke Setup
Changestream requires MySQL, and requires that [row-based replication](https://dev.mysql.com/doc/refman/5.7/en/replication-formats.html)
be enabled on your host. Here is a barebones configuration that is suitable for development:

#### [Binary Log File Position Based Replication](http://dev.mysql.com/doc/refman/5.7/en/binlog-replication-configuration-overview.html)
```
[mysqld]
log-bin=mysql-bin
binlog_format=row
[binlog_rows_query_log_events](https://dev.mysql.com/doc/refman/5.6/en/replication-options-binary-log.html#option_mysqld_binlog-rows-query-log-events) # Optional (MySQL 5.6+ only): to include the original query associated with a mutation event
server-id=952 # must be different than the same setting in application.conf
expire_logs_days=1
```

#### [Replication with Global Transaction Identifiers](http://dev.mysql.com/doc/refman/5.7/en/replication-gtids.html) (MySQL 5.6+)
```
[mysqld]
log-bin=mysql-bin
gtid_mode=on
log-slave-updates
enforce-gtid-consistency
binlog_format=row
binlog_rows_query_log_events # Optional: if you want to obtain the original query associated with the mutation events
server-id=952 # must be different than the same setting in application.conf
expire_logs_days=1
```

#### [Setting up a Replication User](https://dev.mysql.com/doc/refman/5.7/en/replication-howto-repuser.html)
Changestream connects to the master using a user name and password, so there must be
a user account on the master with the REPLICATION SLAVE privilege.

Further, in order to allow changestream to fetch column metadata, the user account
must have SELECT permissions on all user databases and tables.

```
CREATE USER 'changestream'@'%.mydomain.com' IDENTIFIED BY 'changestreampass';
GRANT REPLICATION CLIENT ON *.* TO 'changestream'@'%.mydomain.com';
GRANT REPLICATION SLAVE ON *.* TO 'changestream'@'%.mydomain.com';
GRANT SELECT ON *.* TO 'changestream'@'%.mydomain.com';
```

### Configuration (application.conf/ENV)

#### MySQL Configuration
Ensure that the MySQL authentication info in application.conf is correct. You can ovveride the defaults during development by creating a `src/main/scala/resources/application.overrides.conf` file, which is git ignored:

```
changestream {
		mysql {
    	host = "localhost"
	    port = 3306
    	user = "changestream"
	    password = "changestreampass"
	}
}
```

You can also configure most settings using environment variables. For example, you could put the following in a Changestream init script:

```
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_USER=changestream
export MYSQL_PASS=changestreampass

```

#### Emitter Configuration
If you would like to override the default emitter (`SnsActor`), you can do so by setting `changestream.emitter` or the `EMITTER` environment variable to the fully qualified class name (for example, `changestream.actors.StdoutActor`).

To configure the SNS emitter, you must [provide AWS credentials](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#config-settings-and-precedence), and configure `changestream.aws.sns.topic`.

### Building and Running Changestream
#### SBT
You can build and run Changestream using the [SBT CLI](http://www.scala-sbt.org/0.13/docs/Command-Line-Reference.html):

```
$ sbt run
```

#### IntelliJ
If you are planning to do development on Changestream, you can open the project using [IntelliJ](https://www.jetbrains.com/idea/). Changestream provides valid build, test and run (with debug!) configurations via the `.idea` folder.

## Production Deployment
There are several ways to get changestream running in your production environment. The easiest is...

### Docker (requires a docker host to build)
Note: The Dockerfile will be written to `target/docker/Dockerfile`, and the docker image will be added to your local docker repo.

```
$ sbt docker
```

### Debian (requires `dpkg-deb`, written to `target/*.deb`)
```
$ sbt debian:packageBin
```

### Jar Packages (written to `target/scala-2.11/*.jar`)
```
$ sbt package
```

## The Stack
Changestream is designed to be fast, stable and massively scalable, so it is built on:

- **[Akka](http://akka.io/)** for multi-threaded, asyncronous, and 100% non-blocking processing of events, from our friends at [Lightbend](https://www.lightbend.com/)
- **[shyiko/mysql-binlog-connector-java](https://github.com/shyiko/mysql-binlog-connector-java)** by [Stanley Shyiko](https://github.com/shyiko) to consume the raw MySQL binlog (did I mention its awesome, [and stable too!](http://devs.mailchimp.com/blog/powering-mailchimp-pro-reporting))
- **[mauricio/postgresql-async](https://github.com/mauricio/postgresql-async)** Async, Netty based, database drivers for PostgreSQL and MySQL written in Scala, by [Maurício Linhares](https://github.com/mauricio)
- **[AWS Java SDK](https://aws.amazon.com/sdk-for-java/)**
- **[dwhjames/aws-wrap](https://github.com/dwhjames/aws-wrap)** Asynchronous Scala Clients for Amazon Web Services by [Daniel James](https://github.com/dwhjames)
- **[spray/spray-json](https://github.com/spray/spray-json)** A lightweight, clean and simple JSON implementation in Scala. Used by [Spray](http://spray.io/)/[akka-http](http://doc.akka.io/docs/akka/current/scala.html)


## Architecture
Changestream implements a custom [deserializer](https://github.com/shyiko/mysql-binlog-connector-java#controlling-event-deserialization) for binlog change events, and uses Akka Actors to perform several transformation steps to produce a formatted (pretty) JSON representation of the changes.

The architecture of Changestream is designed so that event format and destination is easily extended with custom Formatter and Emitter classes.

It is also designed with performance in mind. By separating the various steps of event deserialization, column information, formatting/encrypting, and emitting, it is easy to configure Akka such that these steps can be performed in different threads or even on different machines via Akka Remoting.

### Application
#### ChangeStream
The `ChangeStream` object is an instance of `scala.App`, and is the entry point into the application. It's responsibilities are minimal: it loads the `BinaryLogClient` configuration, configures and registers the `ChangeStreamEventListener`, and signals the `BinaryLogClient` to connect and begin processing events.

#### ChangeStreamEventListener / ChangeStreamEventDeserializer
The `ChangeStreamEventListener` and `ChangeStreamEventDeserializer` handles the `BinaryLogClient`'s `onEvent` method, and:

- Converts the java objects that represent binlog event data into beautiful, easy to use [case classes](http://docs.scala-lang.org/tutorials/tour/case-classes.html). *See the `changestream.events` package for a list of all of the case classes.*
- Filters mutation events based on the configurable whitelist or blacklist
- Provides database, table name and sql query information to mutation events
- Dispatches events to the various actors in the system.

### The Actor System
#### TransactionActor
The `TransactionActor` is responsible for maintaining state about 
[MySQL Transactions](http://dev.mysql.com/doc/refman/5.7/en/sql-syntax-transactions.html) that may be in progress, and 
provides the `JsonFormatterActor` with transaction metadata that can be appended to the JSON payload.

#### ColumnInfoActor
Arguably the most complicated actor in the system, it is responsible for fetching primary key information, column names, 
and data types from MySQL via a sidechannel (standard MySQL client connection). This information is used by the 
`JsonFormatterActor` to provide the changed data in a nice human-readable format.

#### JsonFormatterActor
The `JsonFormatterActor` is responsible for taking in information from all of the upstream actors, and emitting a JSON
formatted string. Encryption can be optionally enabled, in which case the formatted mutation
[JsObject](https://github.com/spray/spray-json#usage) is routed through the EncryptorActor before being formatted and
sent to the emitter.

#### EncryptorActor
The `EncryptionActor` provides optional encryption for the change event payloads. The `Plaintext` message is a case
class that accepts a `JsObject` to encrypt. Each of the fields specified in the `changestream.encryptor.encrypt-fields`
setting is stringified via the `compactPrint` method, encrypted, base64 encoded, and finally wrapped in a `JsString`
value. The new `JsObject` with encrypted fields is then returned to `sender()`.

#### SnsActor
The `SnsActor` manages a connection to Amazon SNS, and emits JSON-formatted change events to a configurable SNS topic. 
This publisher is ideal if you wish to leverage SNS for 
[Fan-out](http://docs.aws.amazon.com/sns/latest/dg/SNS_Scenarios.html) to many queues.

#### SqsActor
The `SqsActor` manages a pool of connections to Amazon SQS, and emits JSON-formatted change events to a configurable SQS 
queue. This publisher is ideal if you intend to have a single service consuming events from Changestream.

#### StdoutActor
The `StdoutActor` is primarily included for debugging and example purposes, and simply prints the stringified mutation 
to `STDOUT`. If you intend to create a custom emitter, this could be a good place to start.

#### MyNewProducerActor
You probably see where this is going. More producer actors are welcome! They couldn't be easier to write, since all the 
heavy lifting of creating a JSON string is already done!

```
package changestream.actors

import akka.actor.Actor
import org.slf4j.LoggerFactory

class MyNewProducerActor extends Actor {
  protected val log = LoggerFactory.getLogger(getClass)

  def receive = {
    case message: String =>
      log.debug(s"Received message: ${message}")
      // Do cool stuff

    case _ =>
      log.error("Received invalid message")
      sender() ! akka.actor.Status.Failure(new Exception("Received invalid message"))
  }
}

```


## Binlog Event Routing
The following [MySQL binlog](https://dev.mysql.com/doc/internals/en/binary-log.html)
events are dispatched by the `ChangeStreamEventListener`:

#### TABLE_MAP
Used for row-based binary logging. This event precedes each row
operation event. It maps a table definition to a number, where the
table definition consists of database and table names and column
definitions. The purpose of this event is to enable replication when
a table has different definitions on the master and slave. Row
operation events that belong to the same transaction may be grouped
into sequences, in which case each such sequence of events begins
with a sequence of `TABLE_MAP_EVENT` events: one per table used by
events in the sequence.

[`TABLE_MAP`](https://dev.mysql.com/doc/internals/en/table-map-event.html)
events are used by the `ChangeStreamEventDeserializer` to deserialize `MutationEvent`s
that include `database` and `tableName` info.

#### ROWS_QUERY
Contains the SQL query that was executed to produce the row events
immediately following this event.

[`ROWS_QUERY`](https://dev.mysql.com/doc/internals/en/rows-query-event.html)
events are used by the `ChangeStreamEventDeserializer` to deserialize `MutationEvent`s
that include the event's `sql` query string.

*Note: In order to include sql with the mutation events, you must enable the
`binlog_rows_query_log_events` setting in `my.cnf`.

#### QUERY
[`QUERY`](https://dev.mysql.com/doc/internals/en/query-event.html) events are converted into one of several objects:

- `AlterTableEvent` is delivered to the `ColumnInfoActor` *(signaling that the table's cache should be expired and refreshed)*
- `BeginTransaction` is delivered to the `TransactionActor`
- `CommitTransaction` is delivered to the `TransactionActor`
- `RollbackTransaction` is delivered to the `TransactionActor`

####ROWS_EVENT (aka Mutations)
Mutations (i.e. Inserts, Updates and Deletes) can be represented by several binlog
events, together referred to as
[`ROWS_EVENT`](https://dev.mysql.com/doc/internals/en/rows-event.html):

- `WRITE_ROWS` and `EXT_WRITE_ROWS` are converted to `Insert` and delivered to the `TransactionActor`.
- `UPDATE_ROWS` and `EXT_UPDATE_ROWS` are converted to `Update` and delivered to the `TransactionActor`.
	- *Note: Update events are different from Insert and Delete in that they contain both the old and the new values for the record.*
- `DELETE_ROWS` and `EXT_DELETE_ROWS` are converted to `Delete` and delivered to the `TransactionActor`.

####XID
Generated for a commit of a transaction that modifies one or more tables of an XA-capable
storage engine. Normal transactions are implemented by sending a `QUERY_EVENT` containing a
`BEGIN` statement and a `QUERY_EVENT` containing a `COMMIT` statement (or a `ROLLBACK` statement
if the transaction is rolled back).

`XID_EVENT` events, indicative of the end of a transaction, are converted to
`CommitTransaction` and delivered to the `TransactionActor`.

####GTID
Written to the binlog prior to the mutations in it's transaction, `GTID` event contains the gtid of the
transaction, and is added to each mutation event to allow consumers to group events by transaction (if desired).

`GTID` events are converted to `GtidEvent` and delivered to the `TransactionActor`.


## The Mutation Lifecycle
### Mutation Metadata
The `MutationWithInfo` case class is a container that holds a mutation, and contains slots for various information that will be provided to this event as it is prepared for formatting (implemented via the [`Option`](http://danielwestheide.com/blog/2012/12/19/the-neophytes-guide-to-scala-part-5-the-option-type.html) pattern).

#### MutationEvent
`MutationEvent` contains information about the row or rows that changed. This field is required. All other fields in the case class are initally optional to allow the information to be provided as the event makes its way to the `JsonFormatterActor`.

A `MutationEvent` must have the following information in order to be formatted correctly:

#### TransactionInfo
`TransactionInfo` contains metadata about the transaction to which the mutation belongs, or [`None`](http://www.scala-lang.org/api/2.7.0/scala/None$object.html) if the mutation is not part of a transaction.

#### ColumnsInfo
`ColumnsInfo` contains information about all of the columns in the table to which the mutation belongs.

### Mutation Routing
The `MutationWithInfo` container object travels in a fixed path through the system, gathering metadata along the way.

For those familiar with [Akka Streams](http://doc.akka.io/docs/akka/current/scala/stream/stream-introduction.html), the parantheticals should help to show how events move through the actor system. *Also, for those familiar with Akka Streams, please help me migrate Changestream to Akka Streams.*

#### ChangeStreamEventListener.onEvent (Source)
- Creates/Emits: `MutationWithInfo(MutationEvent, None, None, None)`

#### TransactionActor (Flow)
- Receives: `MutationWithInfo(MutationEvent, None, None)`
- Emits: `MutationWithInfo(MutationEvent, TransactionInfo, None, None)` or `MutationWithInfo(MutationEvent, None, None, None)` (if there is no transaction)

#### ColumnInfoActor (Flow)
- Receives: `MutationWithInfo(MutationEvent, Option[TransactionInfo], None, None)`
- Emits: `MutationWithInfo(MutationEvent, Option[TransactionInfo], ColumnsInfo, None)`

#### JsonFormatterActor (Flow)
- Receives: `MutationWithInfo(MutationEvent, Option[TransactionInfo], ColumnsInfo, None)`
- Emits: `MutationWithInfo(MutationEvent, Option[TransactionInfo], ColumnsInfo, Some(String))`

##### EncryptorActor (Flow)
Optional. The `EncryptorActor` is a child actor of `JsonFormatterActor`, and if enabled, will
receive the completed `JsObject` and encrypt fields in the object based on the `changestream.encryptor.encrypt-fields` setting.

- Receives: `Plaintext(JsObject)`
- Emits: `JsObject`

#### SnsActor / SqsActor / StdoutActor (Sink)
In order to enable features such as string interpolation for the configured topic name, we pass the
`MutationWithInfo` all the way through the process.
- Receives: `MutationWithInfo(MutationEvent, Option[TransactionInfo], ColumnsInfo, Some(String))`


## Requirements
Changestream is built against

- Scala 2.11
- Akka 2.4
- SBT 0.13
- MySQL 5.7 (5.5+ supported)

## Contributing

### Testing
Changestream is 100% unit and integration tested, with end-to-end testing in [CircleCI](https://circleci.com/gh/mavenlink/change-stream) with MySQL and Amazon SNS.

### Mysql Test Config
In order to run tests locally you need to configure `test.conf` with a working MySQL connection configuration.

Make sure that your test user has permissions to create and drop databases and tables. By default, Changestream uses the `changestream_test` table for tests.

### AWS Test Config
To run the integration tests, as well as the unit tests on the `SqsActor` and `SnsActor`, you also need to ensure that you have configured your
AWS tokens to be available from the environment, and that your AWS tokens have access to create and add messages to queues (SQS) and create and
publish to topics (SNS).

```
AWS_ACCESS_KEY_ID=<access_key_id>
AWS_SECRET_ACCESS_KEY=<secret_access_key>
AWS_REGION=<your_region>
```

### Running Tests / IDE
Once that's all set up, you can either run

```
$ sbt test
```

from the command line, or open Changestream using [IntelliJ](https://www.jetbrains.com/idea/) (with the [Scala](https://plugins.jetbrains.com/plugin/?id=1347) and [SBT](https://plugins.jetbrains.com/plugin/5007) plugins installed).

### ScalaStyle
Changestream uses ScalaStyle for code linting--it is not automatic. Before submitting a PR, please take a look at the ScalaStyle output at the top of `sbt test` and correct any issues you find.


## License
Changestream is available under the [MIT License](https://github.com/mavenlink/change-stream/blob/master/LICENSE).


## Authors
[Peter Fry](https://github.com/racerpeter) ([@racerpeter](https://twitter.com/racerpeter)), and the [Mavengineering](http://www.mavengineering.com/archive) team.


## Inspiration
Changestream was inspired in a large part by [mardambey/mypipe](https://github.com/mardambey/mypipe) by [Hisham Mardam-Bey](https://github.com/mardambey). My first proof-of-concept for changestream was a fork of mypipe, thank you Hisham.
