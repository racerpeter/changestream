# Changestream Changelog

## 0.2.2 (2017-08-24)

- Use `StdoutActor` as the default emitter actor, making it easier to get started and test changestream.
- Add the S3 Emitter Actor, with configurable `AWS_S3_BUCKET`, `AWS_S3_KEY_PREFIX`, `AWS_S3_BATCH_SIZE`, and `AWS_S3_FLUSH_TIMEOUT`
- Fixed a bug where the `AWS_REGION` environment variable was being ignored
