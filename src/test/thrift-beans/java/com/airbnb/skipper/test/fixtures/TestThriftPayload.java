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
 * OSS test fixture equivalent to the {@code TestThriftPayload} struct in {@code
 * src/test/thrift/skipper_test_thrift.thrift}.
 *
 * <p>The Gradle (OSS) build has no Thrift compiler on its toolchain, so this bean is authored by
 * hand rather than generated. It is a plain {@link org.apache.thrift.TBase} — exactly what {@code
 * ThriftSerde} serializes — implementing a correct, symmetric {@code TCompactProtocol} read/write
 * (the protocol {@code ThriftSerde} uses) plus value {@code equals}/{@code hashCode} so the serde
 * round-trip tests ({@code ThriftSerdeTest} / {@code SmartSerdeTest}) can assert equality. It is
 * compatible with the pinned {@code libthrift} 0.9.3 runtime.
 *
 * <p>This file lives under {@code src/test/thrift-beans} — a Gradle-only source directory holding
 * the hand-authored Thrift beans (the OSS toolchain has no Thrift compiler to generate them from
 * the {@code .thrift} IDL).
 */
public class TestThriftPayload implements TBase<TestThriftPayload, TestThriftPayload._Fields> {

  private static final TStruct STRUCT_DESC = new TStruct("TestThriftPayload");
  private static final TField HUMAN_READABLE_FIELD_DESC =
      new TField("humanReadable", TType.STRING, (short) 1);

  private String humanReadable;

  public TestThriftPayload() {}

  /** Copy constructor backing {@link #deepCopy()}. */
  public TestThriftPayload(TestThriftPayload other) {
    if (other.isSetHumanReadable()) {
      this.humanReadable = other.humanReadable;
    }
  }

  public String getHumanReadable() {
    return humanReadable;
  }

  public TestThriftPayload setHumanReadable(String humanReadable) {
    this.humanReadable = humanReadable;
    return this;
  }

  public boolean isSetHumanReadable() {
    return humanReadable != null;
  }

  /** The struct's fields, keyed by their Thrift id, mirroring generated {@code _Fields} enums. */
  public enum _Fields implements TFieldIdEnum {
    HUMAN_READABLE((short) 1, "humanReadable");

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
    return fieldId == 1 ? _Fields.HUMAN_READABLE : null;
  }

  @Override
  public boolean isSet(_Fields field) {
    return field == _Fields.HUMAN_READABLE && isSetHumanReadable();
  }

  @Override
  public Object getFieldValue(_Fields field) {
    if (field == _Fields.HUMAN_READABLE) {
      return getHumanReadable();
    }
    throw new IllegalStateException("Unknown field: " + field);
  }

  @Override
  public void setFieldValue(_Fields field, Object value) {
    if (field == _Fields.HUMAN_READABLE) {
      this.humanReadable = (String) value;
      return;
    }
    throw new IllegalStateException("Unknown field: " + field);
  }

  @Override
  public TestThriftPayload deepCopy() {
    return new TestThriftPayload(this);
  }

  @Override
  public void clear() {
    this.humanReadable = null;
  }

  @Override
  public void read(TProtocol iprot) throws TException {
    iprot.readStructBegin();
    while (true) {
      TField field = iprot.readFieldBegin();
      if (field.type == TType.STOP) {
        break;
      }
      if (field.id == 1 && field.type == TType.STRING) {
        this.humanReadable = iprot.readString();
      } else {
        TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();
  }

  @Override
  public void write(TProtocol oprot) throws TException {
    oprot.writeStructBegin(STRUCT_DESC);
    if (humanReadable != null) {
      oprot.writeFieldBegin(HUMAN_READABLE_FIELD_DESC);
      oprot.writeString(humanReadable);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public int compareTo(TestThriftPayload other) {
    int cmp = Boolean.compare(isSetHumanReadable(), other.isSetHumanReadable());
    if (cmp != 0) {
      return cmp;
    }
    if (isSetHumanReadable()) {
      return TBaseHelper.compareTo(this.humanReadable, other.humanReadable);
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TestThriftPayload)) {
      return false;
    }
    return Objects.equals(humanReadable, ((TestThriftPayload) o).humanReadable);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(humanReadable);
  }

  @Override
  public String toString() {
    return "TestThriftPayload(humanReadable=" + humanReadable + ")";
  }
}
