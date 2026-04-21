# Amazon DynamoDB JDBC Driver | DbSchema DynamoDB Designer

Full-compatible JDBC driver provided by [Wise Coders GmbH](https://wisecoders.com) for integrating
[DbSchema Database Designer](https://dbschema.com) with Amazon DynamoDB.

The driver connects to DynamoDB, exposes its structure through standard JDBC `DatabaseMetaData`
(tables, columns, primary keys, indexes), and executes read/write statements using
[Amazon PartiQL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-reference.html).


## Feature List

* Connect to any AWS DynamoDB endpoint (commercial regions, GovCloud, local endpoints)
* List tables, columns, primary keys, and global/local secondary indexes via `DatabaseMetaData`
* Execute `SELECT`, `INSERT`, `UPDATE`, `DELETE` statements using DynamoDB PartiQL
* Parameter binding with `PreparedStatement` (`setString`, `setInt`, `setObject`, …)
* Region auto-detected from the endpoint hostname — no extra configuration needed
* Standard JDBC credentials (`user` / `password`) map to AWS Access Key ID / Secret Access Key


## Licensing

[CC-BY-ND – Attribution-NoDerivs](https://creativecommons.org/licenses/by-nd/4.0/).
The driver is free to use by everyone.
Code modifications are allowed only via pull requests to this repository:
https://github.com/dbschema-pro/jdbc-driver-dynamodb


## Download JDBC Driver Binary Distribution

The driver can be downloaded from the [DbSchema website](https://dbschema.com/jdbc-drivers/dynamodbjdbcdriver.zip).
Unpack and add all JARs to your classpath. The driver requires Java 11 or later.


## JDBC URL Format

```
jdbc:dynamodb://<endpoint>[:<port>]
```

| Part | Description | Example |
|------|-------------|---------|
| `<endpoint>` | DynamoDB service hostname | `dynamodb.eu-central-1.amazonaws.com` |
| `<port>` | Optional TCP port (default: 443 for HTTPS, 80 for localhost) | `8000` |

The AWS region is derived automatically from the hostname pattern
`dynamodb.<region>.amazonaws.com`. For local or custom endpoints pass the
`region` query parameter to override.

**Examples**

```
# AWS commercial endpoint (region eu-central-1 derived from hostname)
jdbc:dynamodb://dynamodb.eu-central-1.amazonaws.com

# AWS US East 1
jdbc:dynamodb://dynamodb.us-east-1.amazonaws.com

# Local DynamoDB (DynamoDB Local / LocalStack)
jdbc:dynamodb://localhost:8000

# Custom endpoint with explicit region override
jdbc:dynamodb://my-custom-host:443?region=us-west-2
```

### Credentials

Pass credentials as standard JDBC properties — either programmatically or via the
`DriverManager` URL properties:

| Property | Description |
|----------|-------------|
| `user` | AWS Access Key ID |
| `password` | AWS Secret Access Key |

The legacy aliases `accessKeyId` and `secretAccessKey` are also accepted.


## Usage

```java
import java.sql.*;
import java.util.Properties;

Class.forName("com.wisecoders.jdbc.dynamodb.JdbcDriver");

Properties props = new Properties();
props.setProperty("user",     "AKIAIOSFODNN7EXAMPLE");
props.setProperty("password", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

Connection con = DriverManager.getConnection(
    "jdbc:dynamodb://dynamodb.eu-central-1.amazonaws.com", props);

// Run a PartiQL SELECT
PreparedStatement ps = con.prepareStatement(
    "SELECT * FROM \"Orders\" WHERE \"orderId\" = ?");
ps.setString(1, "ORD-001");
ResultSet rs = ps.executeQuery();
while (rs.next()) {
    System.out.println(rs.getString("orderId"));
}

// Run a PartiQL INSERT
ps = con.prepareStatement(
    "INSERT INTO \"Orders\" VALUE {'orderId': ?, 'status': ?}");
ps.setString(1, "ORD-002");
ps.setString(2, "PENDING");
ps.executeUpdate();

con.close();
```


## How the Driver Works

### Connection & Region Detection

`DynamoDbConnection` parses the JDBC URL with `java.net.URI` to extract the host and optional
port. The AWS region is derived from the hostname using the pattern
`dynamodb.<region>.amazonaws.com`. For `localhost` or `127.0.0.1` the driver uses an HTTP
endpoint; all other hosts use HTTPS. Credentials are read from the `user` and `password`
connection properties and passed to the AWS SDK as `AwsBasicCredentials`.

### DatabaseMetaData

`DynamoDBDatabaseMetaData` implements the core discovery methods:

| Method | What it returns |
|--------|-----------------|
| `getTables` | All DynamoDB tables (with optional `LIKE` pattern filtering) |
| `getColumns` | Partition key, sort key, and projected attributes per table |
| `getPrimaryKeys` | Hash key (KEY_SEQ=1) and range key (KEY_SEQ=2) if present |
| `getIndexInfo` | Global Secondary Indexes (GSIs) and Local Secondary Indexes (LSIs) |
| `getExportedKeys` | Placeholder — DynamoDB has no foreign key concept |

Table names are retrieved via paginated `ListTables` API calls. Column and index information
comes from `DescribeTable`.

### Statement Execution (PartiQL)

`DynamoDBPreparedStatement` executes statements via the DynamoDB
[`executeStatement`](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_ExecuteStatement.html)
API. Parameters set with `setXxx()` methods are collected into an ordered list and converted to
`AttributeValue` objects before execution.

* `executeQuery()` — returns a `ResultSet` built from the `items` in the response
* `executeUpdate()` — returns `1` on success for `INSERT`, `UPDATE`, and `DELETE`
* `execute()` — delegates to `executeQuery()` or `executeUpdate()` based on the statement type


## How to Test the Driver

The easiest way to test the driver is to download [DbSchema Database Designer](https://dbschema.com)
and connect to DynamoDB. DbSchema can be evaluated free for 15 days.

In the connection dialog, select **Amazon DynamoDB**, enter your endpoint and credentials, and
DbSchema will introspect your tables and display them as a diagram.

For local development you can run **DynamoDB Local** with Docker:

```bash
docker run -d -p 8000:8000 amazon/dynamodb-local
```

Then connect with:

```
jdbc:dynamodb://localhost:8000
user=dummy
password=dummy
```

No real AWS credentials are needed for DynamoDB Local.


## Building from Source

```bash
./gradlew build
```

To run the integration tests against a real AWS endpoint, create
`src/test/resources/aws-test.properties` (this file is gitignored):

```properties
host=dynamodb.<region>.amazonaws.com
user=<AWS_ACCESS_KEY_ID>
password=<AWS_SECRET_ACCESS_KEY>
```

Then run:

```bash
./gradlew test --tests "com.wisecoders.jdbc.dynamodb.DynamoDBAwsIntegrationTest" \
    --no-configuration-cache --rerun-tasks
```

We welcome contributions. Please open issues or pull requests in this repository.
