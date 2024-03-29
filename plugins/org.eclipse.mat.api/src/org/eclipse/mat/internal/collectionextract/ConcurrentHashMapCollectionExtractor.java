/*******************************************************************************
 * Copyright (c) 2008, 2022 SAP AG, IBM Corporation and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    IBM Corporation - enhancements and fixes
 *    James Livingston - expose collection utils as API
 *******************************************************************************/
package org.eclipse.mat.internal.collectionextract;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.ArrayInt;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ICollectionExtractor;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.ObjectReference;

public class ConcurrentHashMapCollectionExtractor extends HashedMapCollectionExtractorBase
{
    public ConcurrentHashMapCollectionExtractor(String arrayField, String keyField, String valueField)
    {
        super(arrayField, keyField, valueField);
    }

    public boolean hasSize()
    {
        return true;
    }

    public Integer getSize(IObject collection) throws SnapshotException
    {
        IObjectArray segmentsArray = extractBackingArray(collection);
        if (segmentsArray == null)
            return 0;
        ISnapshot snapshot = collection.getSnapshot();
        ICollectionExtractor segmentInfo = getSegmentExtractor();
        int size = 0;

        long[] refs = segmentsArray.getReferenceArray();
        for (long addr : refs)
        {
            if (addr != 0)
            {
                // Also works for unindexed objects
                ObjectReference ref = new ObjectReference(snapshot, addr);
                size += segmentInfo.getSize(ref.getObject());
            }
        }

        return size;
    }

    @Override
    public boolean hasCapacity()
    {
        return true;
    }

    @Override
    public Integer getCapacity(IObject collection) throws SnapshotException
    {
        IObjectArray segmentsArray = extractBackingArray(collection);
        if (segmentsArray == null)
            return 0;
        ISnapshot snapshot = collection.getSnapshot();
        ICollectionExtractor extractor = getSegmentExtractor();
        int result = 0;

        long[] refs = segmentsArray.getReferenceArray();
        for (long addr : refs)
        {
            if (addr != 0)
            {
                // Also works for unindexed objects
                ObjectReference ref = new ObjectReference(snapshot, addr);
                IObject segment = ref.getObject();
                Integer cap = extractor.getCapacity(segment);
                if (cap != null && extractor.hasSize())
                {
                    cap = extractor.getSize(segment);
                }

                if (cap != null)
                    result += cap;
            }
        }
        return result;
    }

    public boolean hasExtractableContents()
    {
        return true;
    }

    public boolean hasExtractableArray()
    {
        return false;
    }

    public IObjectArray extractEntries(IObject coll) throws SnapshotException
    {
        throw new IllegalArgumentException();
    }

    public int[] extractEntryIds(IObject coll) throws SnapshotException
    {
        ISnapshot snapshot = coll.getSnapshot();
        IObjectArray segmentsArray = extractBackingArray(coll);
        if (segmentsArray == null)
            return new int[0];
        ArrayInt result = new ArrayInt();
        ICollectionExtractor segmentInfo = getSegmentExtractor();

        long[] refs = segmentsArray.getReferenceArray();
        for (long addr : refs)
        {
            if (addr != 0)
            {
                // Also works for unindexed objects
                ObjectReference ref = new ObjectReference(snapshot, addr);
                int[] segmentEntries = segmentInfo.extractEntryIds(ref.getObject());
                result.addAll(segmentEntries);
            }
        }

        return result.toArray();
    }

    public Integer getNumberOfNotNullElements(IObject collection) throws SnapshotException
    {
        IObjectArray segmentsArray = extractBackingArray(collection);
        if (segmentsArray == null)
            return null;

        ISnapshot snapshot = collection.getSnapshot();
        ICollectionExtractor segmentInfo = getSegmentExtractor();
        int result = 0;
        long[] refs = segmentsArray.getReferenceArray();
        for (long addr : refs)
        {
            if (addr != 0)
            {
                // Also works for unindexed objects
                ObjectReference ref = new ObjectReference(snapshot, addr);
                result += segmentInfo.getNumberOfNotNullElements(ref.getObject());
            }
        }
        return result;
    }

    private ICollectionExtractor getSegmentExtractor() throws SnapshotException
    {
        return CollectionExtractionUtils.findCollectionExtractor("java.util.concurrent.ConcurrentHashMap$Segment"); //$NON-NLS-1$
    }
}
