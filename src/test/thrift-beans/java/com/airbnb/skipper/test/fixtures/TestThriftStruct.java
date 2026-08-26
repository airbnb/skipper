package com.airbnb.skipper.test.fixtures;

import java.util.Objects;
import org.apache.thrift.TBase;
import org.apache.thrift.TBaseHelper;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;

/**
 * OSS test fixture equivalent to the {@code TestThriftStruct} struct in {@code
 * src/test/thrift/skipper_test_thrift.thrift}: two nested {@link TestThriftPayload} fields plus a
 * string and an i32 scalar, exercising nested-struct and scalar serde round-trips.
 *
 * <p>See {@link TestThriftPayload} for why these beans are hand-authored (no Thrift compiler on the
 * Gradle toolchain) and where they live relative to the build.
 */
public class TestThriftStruct implements TBase<TestThriftStruct, TestThriftStruct._Fields> {

  private static final TStruct STRUCT_DESC = new TStruct("TestThriftStruct");
  private static final TField HEADER_FIELD_DESC = new TField("header", TType.STRUCT, (short) 1);
  private static final TField REQUEST_FIELD_DESC = new TField("request", TType.STRUCT, (short) 2);
  private static final TField NAME_FIELD_DESC = new TField("name", TType.STRING, (short) 3);
  private static final TField COUNT_FIELD_DESC = new TField("count", TType.I32, (short) 4);

  private TestThriftPayload header;
  private TestThriftPayload request;
  private String name;
  private int count;
  private boolean isSetCount;

  public TestThriftStruct() {}

  /** Copy constructor backing {@link #deepCopy()}. */
  public TestThriftStruct(TestThriftStruct other) {
    if (other.isSetHeader()) {
      this.header = new TestThriftPayload(other.header);
    }
    if (other.isSetRequest()) {
      this.request = new TestThriftPayload(other.request);
    }
    if (other.isSetName()) {
      this.name = other.name;
    }
    this.count = other.count;
    this.isSetCount = other.isSetCount;
  }

  public TestThriftPayload getHeader() {
    return header;
  }

  public TestThriftStruct setHeader(TestThriftPayload header) {
    this.header = header;
    return this;
  }

  public boolean isSetHeader() {
    return header != null;
  }

  public TestThriftPayload getRequest() {
    return request;
  }

  public TestThriftStruct setRequest(TestThriftPayload request) {
    this.request = request;
    return this;
  }

  public boolean isSetRequest() {
    return request != null;
  }

  public String getName() {
    return name;
  }

  public TestThriftStruct setName(String name) {
    this.name = name;
    return this;
  }

  public boolean isSetName() {
    return name != null;
  }

  public int getCount() {
    return count;
  }

  public TestThriftStruct setCount(int count) {
    this.count = count;
    this.isSetCount = true;
    return this;
  }

  public boolean isSetCount() {
    return isSetCount;
  }

  /** The struct's fields, keyed by their Thrift id, mirroring generated {@code _Fields} enums. */
  public enum _Fields implements TFieldIdEnum {
    HEADER((short) 1, "header"),
    REQUEST((short) 2, "request"),
    NAME((short) 3, "name"),
    COUNT((short) 4, "count");

    private final short thriftId;
    private final String fieldName;

    _Fields(short thriftId, String fieldName) {
      this.thriftId = thriftId;
      this.fieldName = fieldName;
    }

    @Override
    public short getThriftFieldId() {
      return thriftId;
    }

    @Override
    public String getFieldName() {
      return fieldName;
    }
  }

  @Override
  public _Fields fieldForId(int fieldId) {
    switch (fieldId) {
      case 1:
        return _Fields.HEADER;
      case 2:
        return _Fields.REQUEST;
      case 3:
        return _Fields.NAME;
      case 4:
        return _Fields.COUNT;
      default:
        return null;
    }
  }

  @Override
  public boolean isSet(_Fields field) {
    switch (field) {
      case HEADER:
        return isSetHeader();
      case REQUEST:
        return isSetRequest();
      case NAME:
        return isSetName();
      case COUNT:
        return isSetCount();
      default:
        return false;
    }
  }

  @Override
  public Object getFieldValue(_Fields field) {
    switch (field) {
      case HEADER:
        return getHeader();
      case REQUEST:
        return getRequest();
      case NAME:
        return getName();
      case COUNT:
        return getCount();
      default:
        throw new IllegalStateException("Unknown field: " + field);
    }
  }

  @Override
  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
      case HEADER:
        this.header = (TestThriftPayload) value;
        break;
      case REQUEST:
        this.request = (TestThriftPayload) value;
        break;
      case NAME:
        this.name = (String) value;
        break;
      case COUNT:
        if (value == null) {
          this.isSetCount = false;
          this.count = 0;
        } else {
          setCount((Integer) value);
        }
        break;
      default:
        throw new IllegalStateException("Unknown field: " + field);
    }
  }

  @Override
  public TestThriftStruct deepCopy() {
    return new TestThriftStruct(this);
  }

  @Override
  public void clear() {
    this.header = null;
    this.request = null;
    this.name = null;
    this.count = 0;
    this.isSetCount = false;
  }

  @Override
  public void read(TProtocol iprot) throws TException {
    iprot.readStructBegin();
    while (true) {
      TField field = iprot.readFieldBegin();
      if (field.type == TType.STOP) {
        break;
      }
      switch (field.id) {
        case 1:
          if (field.type == TType.STRUCT) {
            this.header = new TestThriftPayload();
            this.header.read(iprot);
          } else {
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2:
          if (field.type == TType.STRUCT) {
            this.request = new TestThriftPayload();
            this.request.read(iprot);
          } else {
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3:
          if (field.type == TType.STRING) {
            this.name = iprot.readString();
          } else {
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 4:
          if (field.type == TType.I32) {
            this.count = iprot.readI32();
            this.isSetCount = true;
          } else {
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();
  }

  @Override
  public void write(TProtocol oprot) throws TException {
    oprot.writeStructBegin(STRUCT_DESC);
    if (header != null) {
      oprot.writeFieldBegin(HEADER_FIELD_DESC);
      header.write(oprot);
      oprot.writeFieldEnd();
    }
    if (request != null) {
      oprot.writeFieldBegin(REQUEST_FIELD_DESC);
      request.write(oprot);
      oprot.writeFieldEnd();
    }
    if (name != null) {
      oprot.writeFieldBegin(NAME_FIELD_DESC);
      oprot.writeString(name);
      oprot.writeFieldEnd();
    }
    if (isSetCount) {
      oprot.writeFieldBegin(COUNT_FIELD_DESC);
      oprot.writeI32(count);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public int compareTo(TestThriftStruct other) {
    int cmp = Boolean.compare(isSetHeader(), other.isSetHeader());
    if (cmp != 0) {
      return cmp;
    }
    if (isSetHeader()) {
      cmp = this.header.compareTo(other.header);
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = Boolean.compare(isSetRequest(), other.isSetRequest());
    if (cmp != 0) {
      return cmp;
    }
    if (isSetRequest()) {
      cmp = this.request.compareTo(other.request);
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = Boolean.compare(isSetName(), other.isSetName());
    if (cmp != 0) {
      return cmp;
    }
    if (isSetName()) {
      cmp = TBaseHelper.compareTo(this.name, other.name);
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = Boolean.compare(isSetCount(), other.isSetCount());
    if (cmp != 0) {
      return cmp;
    }
    if (isSetCount()) {
      cmp = TBaseHelper.compareTo(this.count, other.count);
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TestThriftStruct)) {
      return false;
    }
    TestThriftStruct that = (TestThriftStruct) o;
    return Objects.equals(header, that.header)
        && Objects.equals(request, that.request)
        && Objects.equals(name, that.name)
        && isSetCount == that.isSetCount
        && (!isSetCount || count == that.count);
  }

  @Override
  public int hashCode() {
    return Objects.hash(header, request, name, isSetCount ? count : 0);
  }

  @Override
  public String toString() {
    return "TestThriftStruct(header="
        + header
        + ", request="
        + request
        + ", name="
        + name
        + ", count="
        + (isSetCount ? Integer.toString(count) : "<unset>")
        + ")";
  }
}
