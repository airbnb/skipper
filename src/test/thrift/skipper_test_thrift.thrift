namespace java com.airbnb.skipper.test.fixtures

# OSS-safe Thrift fixtures for the serde tests (SmartSerde / ThriftSerde).
#
# These deliberately depend on no Airbnb-internal schemas (no external
# thrift-codec dependencies) so the OSS serde test
# suite stays self-contained and shippable. A generated bean is a plain
# org.apache.thrift.TBase, which is exactly what ThriftSerde serializes.

# A small nested struct, so the tests can exercise a Thrift object that contains
# other Thrift structs (mirroring the shape of the previous fixture).
struct TestThriftPayload {
  1: optional string humanReadable;
}

struct TestThriftStruct {
  1: optional TestThriftPayload header;
  2: optional TestThriftPayload request;
  3: optional string name;
  4: optional i32 count;
}
