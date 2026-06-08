# DynamoDB JDBC Driver

Minimal JDBC driver for Amazon DynamoDB, used by [DbSchema](https://dbschema.com).

## Connection URL

```
jdbc:dynamodb://<host>[:<port>]?region=<aws-region>&accessKeyId=<key>&secretAccessKey=<secret>
```

Optional: `endpoint` for DynamoDB Local (e.g. `http://localhost:8000`).

## Example

```java
String url = "jdbc:dynamodb://localhost:8000?region=us-east-1&accessKeyId=dummy&secretAccessKey=dummy&endpoint=http://localhost:8000";
Connection con = DriverManager.getConnection(url);
```

## Tests

Integration tests expect DynamoDB Local on port `8000`.
