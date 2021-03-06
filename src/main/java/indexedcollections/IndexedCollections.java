package indexedcollections;

/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createMutator;
import static me.prettyprint.hector.api.factory.HFactory.createSliceQuery;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import me.prettyprint.cassandra.serializers.ByteBufferSerializer;
import me.prettyprint.cassandra.serializers.BytesArraySerializer;
import me.prettyprint.cassandra.serializers.DynamicCompositeSerializer;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.SerializerTypeInferer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.serializers.TypeInferringSerializer;
import me.prettyprint.cassandra.serializers.UUIDSerializer;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.AbstractComposite;
import me.prettyprint.hector.api.beans.AbstractComposite.Component;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.SliceQuery;

import org.apache.log4j.Logger;

/**
 * Simple indexing library using composite types
 * (https://github.com/edanuff/CassandraCompositeType) to implement indexed
 * collections in Cassandra.
 * 
 * See http://www.anuff.com/2010/07/secondary-indexes-in-cassandra.html for a
 * detailed discussion of the technique used here.
 * 
 * @author Ed Anuff
 * @see <a
 *      href="http://www.anuff.com/2010/07/secondary-indexes-in-cassandra.html">Secondary
 *      indexes in Cassandra</a>
 * @see "org.apache.cassandra.db.marshal.CompositeType"
 * 
 */
public class IndexedCollections {

	private static final Logger logger = Logger
			.getLogger(IndexedCollections.class.getName());

	public static final String DEFAULT_ITEM_CF = "Item";
	public static final String DEFAULT_COLLECTION_CF = "Collection";
	public static final String DEFAULT_ITEM_INDEX_ENTRIES = "Item_Index_Entries";
	public static final String DEFAULT_COLLECTION_INDEX_CF = "Collection_Index";

	public static final byte VALUE_CODE_BYTES = 0;
	public static final byte VALUE_CODE_UTF8 = 1;
	public static final byte VALUE_CODE_UUID = 2;
	public static final byte VALUE_CODE_INT = 3;
	public static final byte VALUE_CODE_MAX = 127;

	public static final int DEFAULT_COUNT = 100;
	public static final int ALL_COUNT = 100000;

	public static final CollectionCFSet defaultCFSet = new CollectionCFSet();

	public static final StringSerializer se = new StringSerializer();
	public static final ByteBufferSerializer be = new ByteBufferSerializer();
	public static final BytesArraySerializer bae = new BytesArraySerializer();
	public static final DynamicCompositeSerializer ce = new DynamicCompositeSerializer();
	public static final LongSerializer le = new LongSerializer();
	public static final UUIDSerializer ue = new UUIDSerializer();

	public static UUID newTimeUUID() {
		com.eaio.uuid.UUID eaioUUID = new com.eaio.uuid.UUID();
		return new UUID(eaioUUID.time, eaioUUID.clockSeqAndNode);
	}

	/**
	 * Convert values to be indexed into types that can be compared by
	 * Cassandra: UTF8Type, UUIDType, IntegerType, and BytesType
	 * 
	 * @param value
	 * @return value transformed into String, UUID, BigInteger, or ByteBuffer
	 */
	public static Object getIndexableValue(Object value) {

		if (value == null) {
			return null;
		}

		// Strings, UUIDs, and BigIntegers map to Cassandra
		// UTF8Type, UUIDType, and IntegerType
		if ((value instanceof String) || (value instanceof UUID)
				|| (value instanceof BigInteger)) {
			return value;
		}

		// For any numeric values, turn them into a long
		// and make them BigIntegers for IntegerType
		if (value instanceof Number) {
			return BigInteger.valueOf(((Number) value).longValue());
		}

		// Anything else, we're going to have to use BytesType
		return TypeInferringSerializer.get().toByteBuffer(value);
	}

	/**
	 * The Cassandra DynamicCompositeType will complain if component values of
	 * two different types are attempted to be compared. The way to prevent this
	 * and still allow for indexes to store different dynamic values is have a
	 * value code component that precedes the actual indexed value component in
	 * the composite. The DynamicCompositeType will first compare the two
	 * components holding the value codes, and if they don't match, then won't
	 * compare the next pair of components, avoiding the DynamicCompositeType
	 * throwing an error.
	 * 
	 * @param value
	 * @return value code
	 */
	public static int getIndexableValueCode(Object value) {
		if (value instanceof String) {
			return VALUE_CODE_UTF8;
		} else if (value instanceof UUID) {
			return VALUE_CODE_UUID;
		} else if (value instanceof Number) {
			return VALUE_CODE_INT;
		} else {
			return VALUE_CODE_BYTES;
		}
	}

	private static <IK> void addIndexInsertion(Mutator<ByteBuffer> batch,
			CollectionCFSet cf, String columnIndexKey, IK itemKey,
			Object columnValue, UUID ts_uuid, long timestamp) {

		logger.info("UPDATE " + cf.getIndex() + " SET composite("
				+ getIndexableValueCode(columnValue) + ", "
				+ getIndexableValue(columnValue) + ", " + itemKey + ", "
				+ ts_uuid + ") = null WHERE KEY = " + columnIndexKey);

		DynamicComposite indexComposite = new DynamicComposite(
				getIndexableValueCode(columnValue),
				getIndexableValue(columnValue), itemKey, ts_uuid);

		batch.addInsertion(se.toByteBuffer(columnIndexKey), cf.getIndex(),
				HFactory.createColumn(indexComposite, new byte[0], timestamp,
						ce, bae));

	}

	private static <IK> void addIndexDeletion(Mutator<ByteBuffer> batch,
			CollectionCFSet cf, String columnIndexKey, IK itemKey,
			Object columnValue, UUID prev_timestamp, long timestamp) {

		logger.info("DELETE composite(" + getIndexableValueCode(columnValue)
				+ ", " + getIndexableValue(columnValue) + ", " + itemKey + ", "
				+ prev_timestamp + ") FROM " + cf.getIndex() + " WHERE KEY = "
				+ columnIndexKey);

		DynamicComposite indexComposite = new DynamicComposite(
				getIndexableValueCode(columnValue),
				getIndexableValue(columnValue), itemKey, prev_timestamp);

		batch.addDeletion(se.toByteBuffer(columnIndexKey), cf.getIndex(),
				indexComposite, ce, timestamp);
	}

	private static <IK> void addEntriesInsertion(Mutator<ByteBuffer> batch,
			CollectionCFSet cf, IK itemKey, Object columnName,
			Object columnValue, UUID ts_uuid, Serializer<IK> itemKeySerializer,
			long timestamp) {

		logger.info("UPDATE " + cf.getEntries() + " SET composite("
				+ columnName + ", " + ts_uuid + ") = composite(" + columnValue
				+ ") WHERE KEY = " + itemKey);

		batch.addInsertion(itemKeySerializer.toByteBuffer(itemKey), cf
				.getEntries(), HFactory.createColumn(new DynamicComposite(
				columnName, ts_uuid), new DynamicComposite(columnValue),
				timestamp, ce, ce));
	}

	private static <IK> void addEntriesDeletion(Mutator<ByteBuffer> batch,
			CollectionCFSet cf, IK itemKey, DynamicComposite columnName,
			Object columnValue, UUID prev_timestamp,
			Serializer<IK> itemKeySerializer, long timestamp) {

		logger.info("DELETE composite(" + columnName + ", " + prev_timestamp
				+ ") FROM " + cf.getEntries() + " WHERE KEY = " + itemKey);

		batch.addDeletion(itemKeySerializer.toByteBuffer(itemKey),
				cf.getEntries(), columnName, ce, timestamp);

	}

	/**
	 * Sets the item column value for an item contained in a set of collections.
	 * 
	 * @param <CK>
	 *            the container's key type
	 * @param <IK>
	 *            the item's key type
	 * @param <N>
	 *            the item's column name type
	 * @param <V>
	 *            the item's column value type
	 * @param ko
	 *            the keyspace operator
	 * @param itemKey
	 *            the item row key
	 * @param columnName
	 *            the name of the column to set
	 * @param columnValue
	 *            the value to set the column to
	 * @param containers
	 *            the set of containers the item is in
	 * @param cf
	 *            the column families to use
	 * @param itemKeySerializer
	 *            the item key serializer
	 * @param nameSerializer
	 *            the column name serializer
	 * @param valueSerializer
	 *            the column value serializer
	 * @param containerKeySerializer
	 *            the container key serializer
	 */
	public static <CK, IK, N, V> void setItemColumn(Keyspace ko, IK itemKey,
			N columnName, V columnValue,
			Set<ContainerCollection<CK>> containers, CollectionCFSet cf,
			Serializer<IK> itemKeySerializer, Serializer<N> nameSerializer,
			Serializer<V> valueSerializer, Serializer<CK> containerKeySerializer) {

		logger.info("SET " + columnName + " = '" + columnValue + "' FOR ITEM "
				+ itemKey);

		long timestamp = HFactory.createClock();
		Mutator<ByteBuffer> batch = createMutator(ko, be);
		UUID ts_uuid = newTimeUUID();

		// Get all know previous index entries for this item's
		// indexed column from the item's index entry list

		SliceQuery<IK, DynamicComposite, DynamicComposite> q = createSliceQuery(
				ko, itemKeySerializer, ce, ce);
		q.setColumnFamily(cf.getEntries());
		q.setKey(itemKey);
		q.setRange(new DynamicComposite(columnName, new UUID(0, 0)),
				new DynamicComposite(columnName, new UUID(Long.MAX_VALUE
						| Long.MIN_VALUE, Long.MAX_VALUE | Long.MIN_VALUE)),
				false, ALL_COUNT);
		QueryResult<ColumnSlice<DynamicComposite, DynamicComposite>> r = q
				.execute();
		ColumnSlice<DynamicComposite, DynamicComposite> slice = r.get();
		List<HColumn<DynamicComposite, DynamicComposite>> entries = slice
				.getColumns();

		logger.info(entries.size() + " previous values for " + columnName
				+ " found in index for removal");

		// Delete all previous index entities from the item's index entry list

		for (HColumn<DynamicComposite, DynamicComposite> entry : entries) {
			UUID prev_timestamp = entry.getName().get(1, ue);
			Object prev_value = entry.getValue().get(0);

			addEntriesDeletion(batch, cf, itemKey, entry.getName(), prev_value,
					prev_timestamp, itemKeySerializer, timestamp);
		}

		// Add the new index entry to the item's index entry list

		if (columnValue != null) {
			addEntriesInsertion(batch, cf, itemKey, columnName, columnValue,
					ts_uuid, itemKeySerializer, timestamp);
		}

		for (ContainerCollection<CK> container : containers) {

			String columnIndexKey = container.getKey() + ":"
					+ columnName.toString();

			// Delete all previous index entities from both the container's
			// index

			for (HColumn<DynamicComposite, DynamicComposite> entry : entries) {
				UUID prev_timestamp = entry.getName().get(1, ue);
				Object prev_value = entry.getValue().get(0);

				addIndexDeletion(batch, cf, columnIndexKey, itemKey,
						prev_value, prev_timestamp, timestamp);

			}

			// Add the new index entry into the container's index

			if (columnValue != null) {
				addIndexInsertion(batch, cf, columnIndexKey, itemKey,
						columnValue, ts_uuid, timestamp);
			}

		}

		// Store the new column value into the item
		// If new value is null, delete the value instead

		if (columnValue != null) {

			logger.info("UPDATE " + cf.getItem() + " SET " + columnName + " = "
					+ columnValue + " WHERE KEY = " + itemKey);
			batch.addInsertion(itemKeySerializer.toByteBuffer(itemKey), cf
					.getItem(), HFactory.createColumn(columnName, columnValue,
					timestamp, nameSerializer, valueSerializer));
		} else {
			logger.info("DELETE " + columnName + " FROM " + cf.getItem()
					+ " WHERE KEY = " + itemKey);
			batch.addDeletion(itemKeySerializer.toByteBuffer(itemKey),
					cf.getItem(), columnName, nameSerializer, timestamp);
		}

		batch.execute();

	}

	/**
	 * Search container.
	 * 
	 * @param <IK>
	 *            the item's key type
	 * @param <CK>
	 *            the container's key type
	 * @param <N>
	 *            the item's column name type
	 * @param ko
	 *            the keyspace operator
	 * @param container
	 *            the ContainerCollection (container key and collection name)
	 * @param columnName
	 *            the item's column name
	 * @param searchValue
	 *            the exact value for the specified column
	 * @param startResult
	 *            the start result row key
	 * @param count
	 *            the number of row keys to return
	 * @param reversed
	 *            search in reverse order
	 * @param cf
	 *            the column family set
	 * @param containerKeySerializer
	 *            the container key serializer
	 * @param itemKeySerializer
	 *            the item key serializer
	 * @param nameSerializer
	 *            the column name serializer
	 * @return the list of row keys for items who's column value matches
	 */
	public static <IK, CK, N> List<IK> searchContainer(Keyspace ko,
			ContainerCollection<CK> container, N columnName,
			Object searchValue, IK startResult, int count, boolean reversed,
			CollectionCFSet cf, Serializer<CK> containerKeySerializer,
			Serializer<IK> itemKeySerializer, Serializer<N> nameSerializer) {

		return searchContainer(ko, container, columnName, searchValue,
				searchValue, true, startResult, count, reversed, cf,
				containerKeySerializer, itemKeySerializer, nameSerializer);
	}

	/**
	 * Search container.
	 * 
	 * @param <IK>
	 *            the item's key type
	 * @param <CK>
	 *            the container's key type
	 * @param <N>
	 *            the item's column name type
	 * @param ko
	 *            the keyspace operator
	 * @param container
	 *            the ContainerCollection (container key and collection name)
	 * @param columnName
	 *            the item's column name
	 * @param startValue
	 *            the start value for the specified column (inclusive)
	 * @param endValue
	 *            the end value for the specified column
	 * @param inclusive
	 *            whether end value for the specified column is inclusive
	 * @param startResult
	 *            the start result row key
	 * @param count
	 *            the number of row keys to return
	 * @param reversed
	 *            search in reverse order
	 * @param cf
	 *            the column family set
	 * @param containerKeySerializer
	 *            the container key serializer
	 * @param itemKeySerializer
	 *            the item key serializer
	 * @param nameSerializer
	 *            the column name serializer
	 * @return the list of row keys for items who's column value matches
	 */
	@SuppressWarnings("unchecked")
	public static <IK, CK, N> List<IK> searchContainer(Keyspace ko,
			ContainerCollection<CK> container, N columnName, Object startValue,
			Object endValue, boolean inclusive, IK startResult, int count,
			boolean reversed, CollectionCFSet cf,
			Serializer<CK> containerKeySerializer,
			Serializer<IK> itemKeySerializer, Serializer<N> nameSerializer) {
		List<IK> items = new ArrayList<IK>();

		String columnIndexKey = container.getKey() + ":"
				+ columnName.toString();

		if (count == 0) {
			count = DEFAULT_COUNT;
		}

		SliceQuery<ByteBuffer, DynamicComposite, ByteBuffer> q = createSliceQuery(
				ko, be, ce, be);
		q.setColumnFamily(cf.getIndex());
		q.setKey(se.toByteBuffer(columnIndexKey));

		DynamicComposite start = null;

		if (startValue == null) {
			if (startResult != null) {
				start = new DynamicComposite(VALUE_CODE_BYTES, new byte[0],
						startResult);
			} else {
				start = new DynamicComposite(VALUE_CODE_BYTES, new byte[0]);
			}
		} else if (startResult != null) {
			start = new DynamicComposite(getIndexableValueCode(startValue),
					getIndexableValue(startValue), startResult);
		} else {
			start = new DynamicComposite(getIndexableValueCode(startValue),
					getIndexableValue(startValue));
		}

		DynamicComposite finish = null;

		if (endValue != null) {
			finish = new DynamicComposite(getIndexableValueCode(endValue),
					getIndexableValue(endValue));
			if (inclusive) {
				@SuppressWarnings("rawtypes")
				Component c = finish.getComponent(1);
				finish.setComponent(1, c.getValue(), c.getSerializer(),
						c.getComparator(),
						AbstractComposite.ComponentEquality.GREATER_THAN_EQUAL);
			}
		}

		q.setRange(start, finish, reversed, count);
		QueryResult<ColumnSlice<DynamicComposite, ByteBuffer>> r = q.execute();
		ColumnSlice<DynamicComposite, ByteBuffer> slice = r.get();
		List<HColumn<DynamicComposite, ByteBuffer>> results = slice
				.getColumns();

		if (results != null) {
			for (HColumn<DynamicComposite, ByteBuffer> result : results) {
				Object value = result.getName().get(1);
				logger.info("Value found: " + value);

				IK key = result.getName().get(2, itemKeySerializer);
				if (key != null) {
					items.add(key);
				}
			}
		}

		return items;
	}

	/**
	 * Adds the item to collection.
	 * 
	 * @param <CK>
	 *            the container's key type
	 * @param <IK>
	 *            the item's key type
	 * @param ko
	 *            the keyspace operator
	 * @param container
	 *            the ContainerCollection (container key and collection name)
	 * @param itemKey
	 *            the item's row key
	 * @param cf
	 *            the column families to use
	 * @param containerKeySerializer
	 *            the container key serializer
	 * @param itemKeySerializer
	 *            the item key serializer
	 */
	public static <CK, IK> void addItemToCollection(Keyspace ko,
			ContainerCollection<CK> container, IK itemKey, CollectionCFSet cf,
			Serializer<IK> itemKeySerializer) {

		createMutator(ko, se).insert(
				container.getKey(),
				cf.getItems(),
				createColumn(itemKey, HFactory.createClock(),
						itemKeySerializer, le));

	}

	public static <CK, IK> List<IK> getItemsInCollection(Keyspace ko,
			ContainerCollection<CK> container, CollectionCFSet cf,
			Serializer<IK> itemKeySerializer) {
		List<IK> keys = new ArrayList<IK>();
		SliceQuery<String, IK, ByteBuffer> q = createSliceQuery(ko, se,
				itemKeySerializer, be);
		q.setColumnFamily(cf.getItems());
		q.setKey(container.getKey());
		q.setRange(null, null, false, ALL_COUNT);
		QueryResult<ColumnSlice<IK, ByteBuffer>> r = q.execute();
		ColumnSlice<IK, ByteBuffer> slice = r.get();
		List<HColumn<IK, ByteBuffer>> results = slice.getColumns();
		for (HColumn<IK, ByteBuffer> column : results) {
			keys.add(column.getName());
		}
		return keys;
	}

	@SuppressWarnings("unchecked")
	public static <T, K> T getAsType(K obj, Serializer<T> st) {
		Serializer<K> so = SerializerTypeInferer.getSerializer(obj);
		if (so == null) {
			return null;
		}
		if (so.getClass().equals(st.getClass())) {
			return (T) obj;
		}
		return st.fromByteBuffer(so.toByteBuffer(obj));
	}

	/**
	 * CollectionCFSet contains the names of the four column families needed to
	 * implement indexed collections. Default CF names are provided, but can be
	 * anything that makes sense for the application.
	 */
	public static class CollectionCFSet {

		private String item = DEFAULT_ITEM_CF;
		private String items = DEFAULT_COLLECTION_CF;
		private String index = DEFAULT_COLLECTION_INDEX_CF;
		private String entries = DEFAULT_ITEM_INDEX_ENTRIES;

		public CollectionCFSet() {
		}

		public CollectionCFSet(String item, String items, String index,
				String entries) {
			this.item = item;
			this.items = items;
			this.index = index;
			this.entries = entries;
		}

		public String getItem() {
			return item;
		}

		public void setItem(String item) {
			this.item = item;
		}

		public String getItems() {
			return items;
		}

		public void setItems(String items) {
			this.items = items;
		}

		public String getIndex() {
			return index;
		}

		public void setIndex(String index) {
			this.index = index;
		}

		public String getEntries() {
			return entries;
		}

		public void setEntries(String entries) {
			this.entries = entries;
		}
	}

	/**
	 * ContainerCollection represents the containing entity's key and collection
	 * name. The assumption is that an entity can have multiple collections,
	 * each with their own name.
	 * 
	 * @param <CK>
	 *            the container's row key type
	 */
	public static class ContainerCollection<CK> {
		private CK ownerKey;
		private String collectionName;

		public ContainerCollection(CK ownerKey, String collectionName) {
			this.ownerKey = ownerKey;
			this.collectionName = collectionName;
		}

		public CK getOwnerKey() {
			return ownerKey;
		}

		public void setOwnerKey(CK ownerKey) {
			this.ownerKey = ownerKey;
		}

		public String getCollectionName() {
			return collectionName;
		}

		public void setCollectionName(String collectionName) {
			this.collectionName = collectionName;
		}

		public String getKey() {
			return ownerKey + ":" + collectionName;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime
					* result
					+ ((collectionName == null) ? 0 : collectionName.hashCode());
			result = prime * result
					+ ((ownerKey == null) ? 0 : ownerKey.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			@SuppressWarnings("rawtypes")
			ContainerCollection other = (ContainerCollection) obj;
			if (collectionName == null) {
				if (other.collectionName != null) {
					return false;
				}
			} else if (!collectionName.equals(other.collectionName)) {
				return false;
			}
			if (ownerKey == null) {
				if (other.ownerKey != null) {
					return false;
				}
			} else if (!ownerKey.equals(other.ownerKey)) {
				return false;
			}
			return true;
		}
	}
}
