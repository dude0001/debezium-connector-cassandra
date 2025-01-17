/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.cassandra.transforms.type.deserializer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.MapType;
import org.apache.cassandra.db.rows.ComplexColumnData;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Values;

import io.debezium.connector.cassandra.transforms.CassandraTypeDeserializer;
import io.debezium.connector.cassandra.transforms.DebeziumTypeDeserializer;

public class MapTypeDeserializer extends CollectionTypeDeserializer<MapType<?, ?>> {

    private final DebeziumTypeDeserializer deserializer;

    public MapTypeDeserializer(DebeziumTypeDeserializer deserializer) {
        this.deserializer = deserializer;
    }

    @Override
    public Object deserialize(AbstractType<?> abstractType, ByteBuffer bb) {
        Map<?, ?> deserializedMap = (Map<?, ?>) deserializer.deserialize(abstractType, bb);
        deserializedMap = processKeyValueInDeserializedMap(abstractType, deserializedMap);
        return Values.convertToMap(getSchemaBuilder(abstractType).build(), deserializedMap);
    }

    @Override
    public SchemaBuilder getSchemaBuilder(AbstractType<?> abstractType) {
        MapType<?, ?> mapType = (MapType<?, ?>) abstractType;
        AbstractType<?> keysType = mapType.getKeysType();
        AbstractType<?> valuesType = mapType.getValuesType();
        Schema keySchema = CassandraTypeDeserializer.getSchemaBuilder(keysType).build();
        Schema valuesSchema = CassandraTypeDeserializer.getSchemaBuilder(valuesType).build();
        return SchemaBuilder.map(keySchema, valuesSchema).optional();
    }

    @Override
    public Object deserialize(MapType<?, ?> mapType, ComplexColumnData ccd) {
        List<ByteBuffer> bbList = mapType.serializedValues(ccd.iterator());
        AbstractType<?> keysType = mapType.getKeysType();
        AbstractType<?> valuesType = mapType.getValuesType();
        Map<Object, Object> deserializedMap = new HashMap<>();
        int i = 0;
        while (i < bbList.size()) {
            ByteBuffer kbb = bbList.get(i++);
            ByteBuffer vbb = bbList.get(i++);
            deserializedMap.put(CassandraTypeDeserializer.deserialize(keysType, kbb), CassandraTypeDeserializer.deserialize(valuesType, vbb));
        }
        return Values.convertToMap(getSchemaBuilder(mapType).build(), deserializedMap);
    }

    /**
     * If elements in a deserialized map is LogicalType, convert each element to fit in Kafka Schema type
     *
     * @param abstractType    the {@link AbstractType} of a column in Cassandra
     * @param deserializedMap Map deserialized from Cassandra
     * @return A deserialized map from Cassandra with each element that fits in Kafka Schema type
     */
    private Map<?, ?> processKeyValueInDeserializedMap(AbstractType<?> abstractType, Map<?, ?> deserializedMap) {
        MapType<?, ?> mapType = (MapType<?, ?>) abstractType;
        AbstractType<?> keysType = mapType.getKeysType();
        AbstractType<?> valuesType = mapType.getValuesType();
        TypeDeserializer keysTypeDeserializer = CassandraTypeDeserializer.getTypeDeserializer(keysType);
        TypeDeserializer valuesTypeDeserializer = CassandraTypeDeserializer.getTypeDeserializer(valuesType);
        Map<Object, Object> resultedMap = new HashMap<>();

        for (Map.Entry<?, ?> entry : deserializedMap.entrySet()) {
            Object key = entry.getKey();
            if (keysTypeDeserializer instanceof LogicalTypeDeserializer) {
                key = ((LogicalTypeDeserializer) keysTypeDeserializer).formatDeserializedValue(keysType, key);
            }
            else if (keysTypeDeserializer instanceof UserDefinedTypeDeserializer || keysTypeDeserializer instanceof TupleTypeDeserializer) {
                key = keysTypeDeserializer.deserialize(keysType, (ByteBuffer) key);
            }
            Object value = entry.getValue();
            if (valuesTypeDeserializer instanceof LogicalTypeDeserializer) {
                value = ((LogicalTypeDeserializer) valuesTypeDeserializer).formatDeserializedValue(valuesType, value);
            }
            else if (valuesTypeDeserializer instanceof UserDefinedTypeDeserializer || valuesTypeDeserializer instanceof TupleTypeDeserializer) {
                value = valuesTypeDeserializer.deserialize(valuesType, (ByteBuffer) value);
            }
            resultedMap.put(key, value);
        }

        return resultedMap;
    }
}
