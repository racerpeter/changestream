# Changestream

[![CircleCI](https://circleci.com/gh/mavenlink/changestream.svg?style=svg)](https://circleci.com/gh/mavenlink/changestream) [![codecov](https://codecov.io/gh/mavenlink/changestream/branch/master/graph/badge.svg)](https://codecov.io/gh/mavenlink/changestream)

Changestream sources object-level change events from a [MySQL Replication Master](http://dev.mysql.com/doc/refman/5.7/en/replication-howto-masterbaseconfig.html) (configured for [row-based replication](http://dev.mysql.com/doc/refman/5.7/en/replication-rbr-usage.html)) by streaming the [MySQL binlog](https://dev.mysql.com/doc/internals/en/binary-log.html) and transforming change events into JSON strings that can be published anywhere.

Currently, [Amazon Simple Queuing Service (SQS)](https://aws.amazon.com/sqs/), [Amazon Simple Notification Service](https://aws.amazon.com/sns/) and [Amazon S3](https://aws.amazon.com/s3/) are supported with optional client-side message encryption via AES.

## Documentation

- [Why SNS+SQS?](docs/why-sns+sqs.md)
- [How Do I Consume My Events?](docs/how-do-i-consume-my-events.md)
- [The Stack](docs/the-stack.md)
- [Architecture](docs/architecture.md)
- [Binlog Event Routing](docs/binlog-event-routing.md)

## Developing

### Requirements

- [Scala 2.11](https://www.scala-lang.org/)
- [Akka 2.5](https://akka.io/)
- [SBT 0.13](https://www.scala-sbt.org/)
- [MySQL 5.7](https://www.mysql.com/) (5.5+ supported)

## Getting Started
### The Easy Way with Docker
The easiest way to get started with Changestream is to simply run:
```
$ docker-compose up
```

After running `docker-compose up`, you will have access to a mysql instance on localhost port 3306 with a root password of "password", and changestream configured to listen for events.

*Changestream is available on [Docker Hub](https://hub.docker.com/r/mavenlink/changestream/).*

### The Hard Way

[The bespoke setup](docs/the-bespoke-setup.md)

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
If you would like to override the default emitter (`StdoutActor`), you can do so by setting `changestream.emitter` or the `EMITTER` environment variable to the fully qualified class name (for example, `changestream.actors.SnsActor`).

To configure the SNS emitter, you must [provide AWS credentials](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#config-settings-and-precedence), and configure `changestream.aws.sns.topic`.

### Building and Running Changestream
#### SBT
You can build and run Changestream using the [SBT CLI](http://www.scala-sbt.org/0.13/docs/Command-Line-Reference.html):

```
$ sbt run
```

#### Build Docker Image (requires a docker host to build)
Note: The Dockerfile will be written to `target/docker/Dockerfile`, and the docker image will be added to your local docker repo.

```
$ sbt docker
```

#### Build Debian Package (requires `dpkg-deb`, written to `target/*.deb`)
```
$ sbt debian:packageBin
```

#### Build Jar Package (written to `target/scala-2.11/*.jar`)
```
$ sbt package
```

#### IntelliJ
If you are planning to do development on Changestream, you can open the project using [IntelliJ](https://www.jetbrains.com/idea/). Changestream provides valid build, test and run (with debug!) configurations via the `.idea` folder.

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
