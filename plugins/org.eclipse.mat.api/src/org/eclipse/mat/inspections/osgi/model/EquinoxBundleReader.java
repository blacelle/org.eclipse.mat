/*******************************************************************************
 * Copyright (c) 2008, 2021 SAP AG and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    James Livingston - expose collection utils as API
 *******************************************************************************/
package org.eclipse.mat.inspections.osgi.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.inspections.ReferenceQuery;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ExtractedCollection;
import org.eclipse.mat.inspections.osgi.model.BundleDescriptor.Type;
import org.eclipse.mat.inspections.osgi.model.eclipse.ConfigurationElement;
import org.eclipse.mat.inspections.osgi.model.eclipse.Extension;
import org.eclipse.mat.inspections.osgi.model.eclipse.ExtensionPoint;
import org.eclipse.mat.internal.MATPlugin;
import org.eclipse.mat.internal.Messages;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.MessageUtil;

public class EquinoxBundleReader implements IBundleReader
{
    private Map<String, Bundle> bundles = new HashMap<String, Bundle>();
    private ISnapshot snapshot;
    private Map<BundleDescriptor, List<Service>> registeredServices = new HashMap<BundleDescriptor, List<Service>>();
    private Map<BundleDescriptor, List<Service>> usedServices = new HashMap<BundleDescriptor, List<Service>>();
    private Map<Long, BundleDescriptor> bundleDescriptors = new HashMap<Long, BundleDescriptor>();
    // bundle name -> ExtensionPoints
    private Map<BundleDescriptor, List<ExtensionPoint>> extensionPointsByBundle = new HashMap<BundleDescriptor, List<ExtensionPoint>>();
    // bundle name -> Extensions
    private Map<BundleDescriptor, List<Extension>> extensionsByBundle = new HashMap<BundleDescriptor, List<Extension>>();
    private static final int STEPS = 1000000;
    private int maxWarnings = 100;

    public EquinoxBundleReader(ISnapshot snapshot)
    {
        this.snapshot = snapshot;
    }

    private enum BundleState
    {
        ACTIVE(Messages.EquinoxBundleReader_State_Active, 32), //
        INSTALLED(Messages.EquinoxBundleReader_State_Installed, 2), //
        RESOLVED(Messages.EquinoxBundleReader_State_Resolved, 4), //
        STARTING(Messages.EquinoxBundleReader_State_Starting, 8), //
        STOPPING(Messages.EquinoxBundleReader_State_Stopping, 16), //
        UNINSTALLED(Messages.EquinoxBundleReader_State_Uninstalled, 1);//

        private String label;
        private int value;

        private BundleState(String label, int value)
        {
            this.label = label;
            this.value = value;
        }

        public String getLabel()
        {
            return label;
        }

        public int getValue()
        {
            return value;
        }
    }

    public OSGiModel readOSGiModel(IProgressListener listener) throws SnapshotException
    {
        listener.beginTask(Messages.EquinoxBundleReader_ProcessListenerBundles, STEPS * 3);
        List<BundleDescriptor> descriptors = getBundleDescriptors(listener);
        List<Service> services = collectServiceInfo(listener);
        List<ExtensionPoint> extensionPoints = collectExtensionsInfo(listener);

        OSGiModel model = new OSGiModel(this, descriptors, services, extensionPoints);
        listener.done();
        return model;

    }

    private List<BundleDescriptor> getBundleDescriptors(IProgressListener listener) throws SnapshotException
    {
        listener.subTask(Messages.EquinoxBundleReader_ReadingBundles);
        Collection<IClass> classes = snapshot.getClassesByName(
                        "org.eclipse.osgi.framework.internal.core.BundleRepository", false); //$NON-NLS-1$
        List<BundleDescriptor> bundleDescriptors = new ArrayList<BundleDescriptor>();
        if (classes == null || classes.isEmpty())
            return bundleDescriptors;

        int nobjs = 0;
        for (IClass clazz : classes)
            nobjs += clazz.getNumberOfObjects();

        for (IClass clazz : classes)
        {
            int[] objs = clazz.getObjectIds();

            for (int i = 0; i < objs.length; i++)
            {
                IInstance obj = (IInstance) snapshot.getObject(objs[i]);
                IObject bundles = (IObject) obj.resolveValue("bundlesByInstallOrder");//$NON-NLS-1$
                for (IObject bundleObject : CollectionExtractionUtils.extractList(bundles))
                {
                    if (listener.isCanceled())
                        throw new IProgressListener.OperationCanceledException();

                    BundleDescriptor.Type type = BundleDescriptor.Type.BUNDLE;
                    if (bundleObject.getClazz().getName()
                                    .equals("org.eclipse.osgi.framework.internal.core.BundleFragment"))//$NON-NLS-1$
                    {
                        type = BundleDescriptor.Type.FRAGMENT;
                    }
                    BundleDescriptor descriptor = getBundleDescriptor(bundleObject, type);
                    bundleDescriptors.add(descriptor);
                }
                listener.worked(STEPS / nobjs);
            }
        }
        return bundleDescriptors;
    }

    private List<BundleDescriptor> getBundleFragments(IObject bundleHostObject) throws SnapshotException
    {
        IObject fragmentObject = (IObject) bundleHostObject.resolveValue("fragments");//$NON-NLS-1$
        if (fragmentObject == null)
            return null;

        // FIXME: change to coll != null?
        if (fragmentObject.getClazz().isArrayType())
        {
            ExtractedCollection coll = CollectionExtractionUtils.extractList(fragmentObject);
            Integer size = coll.size();
            List<BundleDescriptor> fragments = new ArrayList<BundleDescriptor>(size != null ? (int)size : 0);
            for (IObject obj : coll)
            {
                BundleDescriptor descriptor = getBundleDescriptor(obj, Type.FRAGMENT);
                fragments.add(descriptor);
            }
            return fragments;
        }
        else
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedArrayType,
                            Long.toHexString(fragmentObject.getObjectAddress())));
            return null;
        }

    }

    private List<Service> collectServiceInfo(IProgressListener listener) throws SnapshotException
    {
        listener.subTask(Messages.EquinoxBundleReader_ReadingServices);

        Collection<IClass> classes = snapshot.getClassesByName( // 3.5
                        "org.eclipse.osgi.internal.serviceregistry.ServiceRegistry", false); //$NON-NLS-1$
        if (classes == null || classes.isEmpty())
            classes = snapshot.getClassesByName( // 3.4
                            "org.eclipse.osgi.framework.internal.core.ServiceRegistryImpl", false); //$NON-NLS-1$

        List<Service> services = new ArrayList<Service>();
        if (classes == null || classes.isEmpty())
            return services;

        int nobjs = 0;
        for (IClass clazz : classes)
            nobjs += clazz.getNumberOfObjects();

        for (IClass clazz : classes)
        {
            int[] objs = clazz.getObjectIds();

            for (int i = 0; i < objs.length; i++)
            {
                IObject obj = snapshot.getObject(objs[i]);
                IObject publishedServices = (IObject) obj.resolveValue("allPublishedServices"); //$NON-NLS-1$
                for (IObject serviceInstance : CollectionExtractionUtils.extractList(publishedServices))
                {
                    if (listener.isCanceled())
                        throw new IProgressListener.OperationCanceledException();
                    IObject bundleObj = (IObject) serviceInstance.resolveValue("bundle"); //$NON-NLS-1$
                    BundleDescriptor bundleDescriptor = getBundleDescriptor(bundleObj, Type.BUNDLE);

                    List<BundleDescriptor> bundlesUsing = null;
                    IObject bundlesList = (IObject) serviceInstance.resolveValue("contextsUsing");//$NON-NLS-1$
                    ExtractedCollection bunds = CollectionExtractionUtils.extractList(bundlesList);
                    Integer size = bunds.size();
                    bundlesUsing = new ArrayList<BundleDescriptor>(size != null ? (int)size : 0);
                    for (IObject bundleInstance : bunds)
                    {
                        IObject bundleObject = (IObject) bundleInstance.resolveValue("bundle");//$NON-NLS-1$
                        if (bundleObject == null)
                            continue;

                        BundleDescriptor usingBundleDescriptor = getBundleDescriptor(bundleObject, Type.BUNDLE);
                        bundlesUsing.add(usingBundleDescriptor);
                    }
                    // get service name
                    IObjectArray clazzes = (IObjectArray) serviceInstance.resolveValue("clazzes");//$NON-NLS-1$
                    Iterator<IObject> it = CollectionExtractionUtils.extractList(clazzes).iterator();
                    String serviceName = it.next().getClassSpecificName();
                    // get properties
                    IObject propertiesObject = (IObject) serviceInstance.resolveValue("properties");//$NON-NLS-1$
                    String[] keys = null;
                    String[] values = null;
                    if (propertiesObject != null)
                    {
                        IObjectArray keysArray = (IObjectArray) propertiesObject.resolveValue("headers"); //$NON-NLS-1$
                        if (keysArray != null)
                        {
                            long[] keyAddresses = keysArray.getReferenceArray();
                            if (keyAddresses != null)
                            {
                                keys = getServiceProperties(new String[keyAddresses.length], keyAddresses);
                            }
                        }
                        IObjectArray valuesArray = (IObjectArray) propertiesObject.resolveValue("values"); //$NON-NLS-1$
                        if (valuesArray != null)
                        {
                            long[] valueAddresses = valuesArray.getReferenceArray();
                            if (valueAddresses != null)
                            {
                                values = getServiceProperties(new String[valueAddresses.length], valueAddresses);
                            }
                        }
                    }

                    services.add(new Service(serviceName, serviceInstance.getObjectId(), bundleDescriptor,
                                    bundlesUsing, keys, values));
                }
                listener.worked(STEPS / nobjs);
            }
        }

        if (services.size() > 0)
            updateServiceMap(services);
        return services;
    }

    private String[] getServiceProperties(String[] values, long[] valueAddresses)
    {
        for (int j = 0; j < valueAddresses.length; j++)
        {
            if (valueAddresses[j] == 0)
                continue;
            try
            {
                ObjectReference valueRef = new ObjectReference(snapshot, valueAddresses[j]);
                IObject valueObject = valueRef.getObject();
                if (valueObject == null)
                    continue;
                if (valueObject.getClazz().isArrayType())
                {
                    long[] addresses = ((IObjectArray) valueObject).getReferenceArray();

                    for (int k = 0; k < addresses.length; k++)
                    {
                        if (valueAddresses[k] == 0)
                            continue;
                        ObjectReference objectRef = new ObjectReference(snapshot, addresses[k]);
                        IObject object = objectRef.getObject();
                        if (object == null)
                            continue;
                        values[j] = object.getClassSpecificName();
                        break; // is more than one element possible in that
                        // array?
                    }
                }
                else
                {
                    values[j] = valueObject.getClassSpecificName();
                }
            }
            catch (SnapshotException e)
            {
                values[j] = null;
                MATPlugin.log(e,
                                MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ServiceProperty,
                                                Long.toHexString(valueAddresses[j])));
            }
        }
        return values;
    }

    private void updateServiceMap(List<Service> services)
    {
        for (Service service : services)
        {
            BundleDescriptor contributedBy = service.getBundleDescriptor();
            // update registered services
            doUpdate(service, contributedBy, registeredServices);

            // update used services
            List<BundleDescriptor> bundlesUsing = service.getBundlesUsing();
            if (bundlesUsing != null)
                for (BundleDescriptor descriptor : bundlesUsing)
                {
                    doUpdate(service, descriptor, usedServices);
                }
        }

    }

    private void doUpdate(Service service, BundleDescriptor bundleDescriptor,
                    Map<BundleDescriptor, List<Service>> serviceMap)
    {
        List<Service> listOfServices = serviceMap.get(bundleDescriptor);
        if (listOfServices == null)
        {
            List<Service> bundleServices = new ArrayList<Service>(1);
            bundleServices.add(service);
            serviceMap.put(bundleDescriptor, bundleServices);
        }
        else if (!listOfServices.contains(service))
        {
            listOfServices.add(service);
        }
    }

    private BundleDescriptor getBundleDescriptor(IObject bundleHostObject, BundleDescriptor.Type type)
                    throws SnapshotException
    {
        IInstance bundleData = (IInstance) bundleHostObject.resolveValue("bundledata");//$NON-NLS-1$
        Field idField = bundleData.getField("id"); //$NON-NLS-1$
        Long id = null;
        if (idField != null)
        {
            id = (Long) idField.getValue();
        }
        // check whether this bundle descriptor is already in the map
        BundleDescriptor bundleDescriptor = bundleDescriptors.get(id);
        if (bundleDescriptor != null)
            return bundleDescriptor;

        String bundleName = extractBundleName(bundleData);
        String state = getState(bundleHostObject);

        BundleDescriptor descriptor = new BundleDescriptor(bundleHostObject.getObjectId(), id, bundleName, state, type);
        // add new bundle name to the map. Key is bundleId
        bundleDescriptors.put(id, descriptor);

        return descriptor;
    }

    private String extractBundleName(IObject bundleData) throws SnapshotException
    {
        IObject name = (IObject) bundleData.resolveValue("symbolicName");//$NON-NLS-1$
        String symbolicName = name.getClassSpecificName();

        IObject versionObj = (IObject) bundleData.resolveValue("version.qualifier.value");//$NON-NLS-1$
        String version = null;
        if (versionObj != null)
        {
            version = versionObj.getClassSpecificName();
        }
        String bundleName = version == null || version.equals("") ? symbolicName : symbolicName + " (" + version + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return bundleName;
    }

    public Bundle getBundle(BundleDescriptor descriptor) throws SnapshotException
    {
        Bundle bundle = bundles.get(descriptor.getBundleName());
        if (bundle == null)
            bundle = load(descriptor);
        return bundle;

    }

    private Bundle load(BundleDescriptor descriptor) throws SnapshotException
    {
        int objectId = descriptor.getObjectId();
        IInstance obj = (IInstance) snapshot.getObject(objectId);

        IObject locationObj = (IObject) obj.resolveValue("bundledata.fileName");//$NON-NLS-1$
        String location = null;
        if (locationObj != null)
        {
            location = locationObj.getClassSpecificName();
        }
        if (descriptor.getType().equals(BundleDescriptor.Type.FRAGMENT))
        {
            BundleDescriptor host = getFragmentHost(obj);
            return new BundleFragment(descriptor, location, host);
        }
        List<BundleDescriptor> dependencies = null;
        List<BundleDescriptor> dependents = null;

        IObject bundleDescriptionObject = (IObject) obj.resolveValue("bundledata.bundle.proxy.description");//$NON-NLS-1$
        if (bundleDescriptionObject != null)
        {
            IObject resolvedValue = (IObject) bundleDescriptionObject.resolveValue("dependencies");//$NON-NLS-1$
            dependencies = getDependencies(snapshot, resolvedValue);

            IObject resolvedDependentsValue = (IObject) bundleDescriptionObject.resolveValue("dependents");//$NON-NLS-1$
            dependents = getDependencies(snapshot, resolvedDependentsValue);
        }

        List<Extension> extensions = extensionsByBundle.get(descriptor);
        List<ExtensionPoint> extensionPoints = extensionPointsByBundle.get(descriptor);

        List<Service> registeredServices = this.registeredServices.get(descriptor);
        List<Service> usedServices = this.usedServices.get(descriptor);

        List<BundleDescriptor> fragments = getBundleFragments(obj);

        return new Bundle(descriptor, location, dependencies, dependents, extensionPoints, extensions,
                        registeredServices, usedServices, fragments);

    }

    private BundleDescriptor getFragmentHost(IObject bundleFragmentObject) throws SnapshotException
    {
        IObject fragmentObject = (IObject) bundleFragmentObject.resolveValue("hosts");//$NON-NLS-1$
        if (fragmentObject == null)
            return null;

        if (!fragmentObject.getClazz().isArrayType())
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedArrayType,
                            Long.toHexString(fragmentObject.getObjectAddress())));
            return null;
        }

        IObjectArray hosts = (IObjectArray) fragmentObject;
        if (hosts.getLength() == 0)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_BundleNotFound,
                            Long.toHexString(fragmentObject.getObjectAddress())));
            return null;
        }

        long[] addresses = hosts.getReferenceArray();
        IObject bundleObject = null;

        if ("org.eclipse.osgi.framework.internal.core.BundleHost[]".equals( //$NON-NLS-1$
                        fragmentObject.getClazz().getName()))
        {
            // 3.5
            ObjectReference hostRef = new ObjectReference(snapshot, addresses[0]);
            bundleObject = hostRef.getObject();
        }
        else if ("org.eclipse.osgi.framework.internal.core.BundleLoaderProxy[]".equals( //$NON-NLS-1$
                        fragmentObject.getClazz().getName()))
        {
            // 3.4
            ObjectReference hostRef = new ObjectReference(snapshot, addresses[0]);
            IObject bundleLoaderObject = hostRef.getObject();
            bundleObject = (IObject) bundleLoaderObject.resolveValue("bundle");//$NON-NLS-1$
        }

        if (bundleObject == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_BundleNotFound,
                            Long.toHexString(fragmentObject.getObjectAddress())));
            return null;
        }
        else
        {
            return getBundleDescriptor(bundleObject, Type.BUNDLE);
        }
    }

    private String getState(IObject obj) throws SnapshotException
    {
        IInstance bundleHostObject = (IInstance) obj.resolveValue("bundledata.bundle");//$NON-NLS-1$
        Field stateField = bundleHostObject.getField("state");//$NON-NLS-1$
        if (stateField == null)
            return Messages.EquinoxBundleReader_NotApplicable;

        int state = ((Integer) stateField.getValue()).intValue();

        for (BundleState stateType : BundleState.values())
        {
            if (stateType.getValue() == state)
                return stateType.getLabel();
        }

        return Messages.EquinoxBundleReader_NotApplicable;
    }

    private List<BundleDescriptor> getDependencies(ISnapshot snapshot, IObject resolvedValue) throws SnapshotException
    {
        List<BundleDescriptor> dependencyDescriptors = null;
        ExtractedCollection coll = CollectionExtractionUtils.extractList(resolvedValue);
        if (coll != null)
        {
            Integer size = coll.size();
            dependencyDescriptors = new ArrayList<BundleDescriptor>(size != null ? (int)size : 0);
            for (IObject bundleDescriptionObject : coll)
            {
                IObject bundleHostObject = (IObject) bundleDescriptionObject.resolveValue("userObject.bundle");//$NON-NLS-1$
                if (bundleHostObject == null)
                    continue;

                BundleDescriptor.Type type = BundleDescriptor.Type.BUNDLE;
                if (bundleHostObject.getClazz().getName()
                                .equals("org.eclipse.osgi.framework.internal.core.BundleFragment"))//$NON-NLS-1$
                    type = BundleDescriptor.Type.FRAGMENT;

                BundleDescriptor descriptor = getBundleDescriptor(bundleHostObject, type);
                dependencyDescriptors.add(descriptor);
            }
        }
        return dependencyDescriptors;
    }

    private List<ExtensionPoint> collectExtensionsInfo(IProgressListener listener) throws SnapshotException
    {
        listener.subTask(Messages.EquinoxBundleReader_ReadingExtensions);
        Collection<IClass> classes = snapshot.getClassesByName(
                        "org.eclipse.core.internal.registry.ExtensionRegistry", false); //$NON-NLS-1$

        if (classes == null || classes.isEmpty())
            return null;
        Map<String, ExtensionPoint> extensionPoints = new HashMap<String, ExtensionPoint>();
        Map<Integer, Extension> extensions = new HashMap<Integer, Extension>();
        Map<Integer, ConfigurationElement> configElements = new HashMap<Integer, ConfigurationElement>();

        int nobjs = 0;
        for (IClass clazz : classes)
            nobjs += clazz.getNumberOfObjects();

        for (IClass clazz : classes)
        {
            int[] objs = clazz.getObjectIds();

            for (int i = 0; i < objs.length; i++)
            {
                IInstance obj = (IInstance) snapshot.getObject(objs[i]);

                IObjectArray heldObjectsArray = (IObjectArray) obj.resolveValue("registryObjects.heldObjects.elements");//$NON-NLS-1$
                ExtractedCollection heldObjects = CollectionExtractionUtils.extractList(heldObjectsArray);
                if (heldObjects != null)
                {
                    for (IObject instance : heldObjects)
                    {
                        if (listener.isCanceled())
                            throw new IProgressListener.OperationCanceledException();
                        extractElements(instance, extensionPoints, extensions, configElements, listener);
                    }
                }

                IObjectArray cachedObjectsArray = (IObjectArray) obj.resolveValue("registryObjects.cache.table");//$NON-NLS-1$
                // The cached objects can find extras, but causes duplicate
                // extensions, extension points etc.
                boolean useCachedObjects = true;
                if (useCachedObjects && cachedObjectsArray != null)
                {
                    for (IObject instance : CollectionExtractionUtils.extractList(cachedObjectsArray))
                    {
                        if (listener.isCanceled())
                            throw new IProgressListener.OperationCanceledException();

                        ObjectReference ref = ReferenceQuery.getReferent((IInstance) instance);
                        if (ref != null)
                        {
                            instance = ref.getObject();
                        }
                        extractElements(instance, extensionPoints, extensions, configElements, listener);
                    }
                }
                listener.worked(STEPS / nobjs);
            }
        }
        // add ConfigurationElements to corresponding Extensions or
        // ConfigurationElements
        Set<Entry<Integer, ConfigurationElement>> configElementSet = configElements.entrySet();
        for (Entry<Integer, ConfigurationElement> entry : configElementSet)
        {
            if (listener.isCanceled())
                throw new IProgressListener.OperationCanceledException();
            ConfigurationElement element = entry.getValue();
            Extension extension = extensions.get(element.getParentId());
            if (extension == null)
            {
                ConfigurationElement configElement = configElements.get(element.getParentId());
                if (configElement == null)
                    continue;
                configElement.addConfigurationElement(element);
                continue;
            }
            extension.addConfigurationElement(element);
        }

        // add Extensions to corresponding ExtensionPoints
        Set<Entry<Integer, Extension>> set = extensions.entrySet();
        for (Entry<Integer, Extension> entry : set)
        {
            if (listener.isCanceled())
                throw new IProgressListener.OperationCanceledException();
            Extension extension = entry.getValue();
            String name = extension.getName();
            ExtensionPoint extensionPoint = extensionPoints.get(name);
            if (extensionPoint == null)
                continue;
            extensionPoint.addExtension(extension);
        }

        // fill maps extensionsByBundle, ExtensionPointsByBundle
        Set<Entry<Integer, Extension>> extensionsSet = extensions.entrySet();
        for (Entry<Integer, Extension> entry : extensionsSet)
        {
            if (listener.isCanceled())
                throw new IProgressListener.OperationCanceledException();
            Extension extension = entry.getValue();
            BundleDescriptor bundleDescriptor = extension.getContributedBy();

            List<Extension> listOfExtensions = extensionsByBundle.get(bundleDescriptor);
            if (listOfExtensions == null)
            {
                List<Extension> extensionList = new ArrayList<Extension>(1);
                extensionList.add(extension);
                extensionsByBundle.put(bundleDescriptor, extensionList);
            }
            else if (!listOfExtensions.contains(extension))
            {
                listOfExtensions.add(extension);
            }
        }
        Set<Entry<String, ExtensionPoint>> pointsSet = extensionPoints.entrySet();
        for (Entry<String, ExtensionPoint> entry : pointsSet)
        {
            if (listener.isCanceled())
                throw new IProgressListener.OperationCanceledException();
            ExtensionPoint point = entry.getValue();
            BundleDescriptor bundleDescriptor = point.getContributedBy();
            List<ExtensionPoint> listOfExtensions = extensionPointsByBundle.get(bundleDescriptor);
            if (listOfExtensions == null)
            {
                List<ExtensionPoint> bundleExtensions = new ArrayList<ExtensionPoint>(1);
                bundleExtensions.add(point);
                extensionPointsByBundle.put(bundleDescriptor, bundleExtensions);
            }
            else if (!listOfExtensions.contains(point))
            {
                listOfExtensions.add(point);
            }
        }
        // return a list of extension points
        Set<Entry<BundleDescriptor, List<ExtensionPoint>>> extensionPointSet = extensionPointsByBundle.entrySet();
        List<ExtensionPoint> points = new ArrayList<ExtensionPoint>();
        for (Entry<BundleDescriptor, List<ExtensionPoint>> entry : extensionPointSet)
        {
            if (listener.isCanceled())
                throw new IProgressListener.OperationCanceledException();
            List<ExtensionPoint> list = entry.getValue();
            for (ExtensionPoint extensionPoint : list)
            {
                if (!points.contains(extensionPoint))
                    points.add(extensionPoint);
            }
        }
        return points;

    }

    private void extractElements(IObject instance, Map<String, ExtensionPoint> extensionPoints,
                    Map<Integer, Extension> extensions, Map<Integer, ConfigurationElement> configElements,
                    IProgressListener listener) throws SnapshotException
    {
        // get type of object (Extension, ExtensionPoint,
        // ConfigurationElement)
        String className = instance.getClazz().getName();
        if (className.equals("org.eclipse.core.internal.registry.ExtensionPoint")) //$NON-NLS-1$
        {
            ExtensionPoint extensionPoint = extractExtensionPointInfo(instance);
            if (extensionPoint != null && !extensionPoints.containsValue(extensionPoint))
                extensionPoints.put(extensionPoint.getName(), extensionPoint);
            else if (extensionPoint != null && instance.getObjectId() != extensionPoint.getObjectId())
                MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_DuplicateExtensionPoint,
                                extensionPoint.getName(),
                                Long.toHexString(snapshot.mapIdToAddress(extensionPoint.getObjectId())),
                                Long.toHexString(instance.getObjectAddress())));
        }
        else if (className.equals("org.eclipse.core.internal.registry.ConfigurationElement")) //$NON-NLS-1$
        {
            ConfigurationElement configElement = extractConfigurationElementInfo(instance, listener);
            if (configElement != null && !configElements.containsValue(configElement))
                configElements.put(configElement.getElementId(), configElement);
            else if (configElement != null && instance.getObjectId() != configElement.getObjectId())
                MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_DuplicateConfigurationElement,
                                configElement.getName(),
                                Long.toHexString(snapshot.mapIdToAddress(configElement.getObjectId())),
                                Long.toHexString(instance.getObjectAddress())));
        }
        else if (className.equals("org.eclipse.core.internal.registry.Extension")) //$NON-NLS-1$
        {
            Extension extension = extractExtensionInfo(instance);
            if (extension != null && !extensions.containsValue(extension))
                extensions.put(extension.getExtensionId(), extension);
            else if (extension != null && instance.getObjectId() != extension.getObjectId())
                MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_DuplicateExtension,
                                extension.getName(),
                                Long.toHexString(snapshot.mapIdToAddress(extension.getObjectId())),
                                Long.toHexString(instance.getObjectAddress())));
        }
        else
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_UnknownElementType,
                            Long.toHexString(instance.getObjectAddress()), instance.getClazz().getName()));
        }
    }

    private ConfigurationElement extractConfigurationElementInfo(IObject instance, IProgressListener listener)
                    throws SnapshotException
    {
        Field idField = ((IInstance) instance).getField("parentId"); //$NON-NLS-1$
        if (idField == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedFieldParent,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }

        Integer parentId = (Integer) idField.getValue();

        Field objectIdField = ((IInstance) instance).getField("objectId"); //$NON-NLS-1$
        if (objectIdField == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedFieldObjectId,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }

        Integer objectId = (Integer) objectIdField.getValue();

        IObject contributorObject = (IObject) instance.resolveValue("contributorId");//$NON-NLS-1$
        if (contributorObject == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ExpectedFieldContributorId,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }

        /*
         * in some heap dumps the the contributorID was a fully qualified name
         * instead of a number. The following lines are an attempt to read the
         * data as number
         */
        Long contributorId = null;
        BundleDescriptor contributedBy = null;
        String contributorIdString = contributorObject.getClassSpecificName();
        try
        {
            contributorId = Long.valueOf(contributorIdString);
        }
        catch (NumberFormatException e)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_CannotFindContributorID, contributorIdString));
        }
        if (contributorId != null)
        {
            contributedBy = bundleDescriptors.get(contributorId);
        }

        IObject nameObject = (IObject) instance.resolveValue("name");//$NON-NLS-1$
        if (nameObject == null)
        {
            // some configuration elements contain only description. In that
            // case attribute name is not available.
            return null;
        }
        String name = nameObject.getClassSpecificName();
        IObject propertiesObject = (IObject) instance.resolveValue("propertiesAndValue");//$NON-NLS-1$
        if (propertiesObject == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ExpectedFieldPropertiesAndValues,
                            Long.toHexString(instance.getObjectAddress())));
        }
        else if (propertiesObject.getClazz().isArrayType())
        {
            long[] addresses = ((IObjectArray) propertiesObject).getReferenceArray();
            String[] propertiesAndValues = new String[addresses.length];
            for (int i = 0; i < addresses.length; i++)
            {
                if (listener.isCanceled())
                    throw new IProgressListener.OperationCanceledException();
                if (addresses[i] == 0)
                    continue;
                try
                {
                    ObjectReference or = new ObjectReference(snapshot, addresses[i]);

                    IObject object = or.getObject();
                    propertiesAndValues[i] = object.getClassSpecificName();
                }
                catch (SnapshotException e)
                {
                    // Some HPROF dumps have String arrays with invalid entries
                    // Generating 10,000 messages takes too long
                    if (maxWarnings-- > 0)
                        MATPlugin.log(e,
                                        MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ReadingProperty,
                                                        Long.toHexString(addresses[i])));
                    propertiesAndValues[i] = null;
                }

            }
            return new ConfigurationElement(instance.getObjectId(), name, parentId, objectId, contributedBy,
                            propertiesAndValues);
        }
        else
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedStringArray,
                            Long.toHexString(instance.getObjectAddress())));
        }
        return new ConfigurationElement(instance.getObjectId(), name, parentId, objectId, contributedBy, null);

    }

    private Extension extractExtensionInfo(IObject instance) throws SnapshotException
    {
        // Expect at least 3 properties
        String[] properties = getExtensionProperties(instance);
        if (properties == null)
            return null;

        Field id = ((IInstance) instance).getField("objectId"); //$NON-NLS-1$
        if (id == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedFieldObjectId,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }
        Integer extensionId = (Integer) id.getValue();
        Extension extension = new Extension(instance.getObjectId(), extensionId, properties);

        /*
         * in some heap dumps the the contributorID was a fully qualified name
         * instead of a number. The following lines are an attempt to read the
         * data as number
         */
        Long contributorId = null;
        String contributorIdString = extension.getContributorId();
        try
        {
            contributorId = Long.valueOf(contributorIdString);
        }
        catch (NumberFormatException e)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_CannotFindContributorID, contributorIdString));
        }
        if (contributorId != null)
        {
            BundleDescriptor contributedBy = bundleDescriptors.get(contributorId);
            extension.setContributedBy(contributedBy);
        }

        return extension;
    }

    private String[] getExtensionProperties(IObject instance) throws SnapshotException
    {
        IObject extraInfoObject = (IObject) instance.resolveValue("extraInformation");//$NON-NLS-1$
        if (extraInfoObject == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedFieldExtraInformation,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }

        if (extraInfoObject instanceof IInstance)
        {
            // Handle soft references
            ObjectReference ref = ReferenceQuery.getReferent((IInstance) extraInfoObject);
            if (ref != null)
            {
                extraInfoObject = ref.getObject();
            }
        }

        if (extraInfoObject.getClazz().isArrayType())
        {
            long[] addresses = ((IObjectArray) extraInfoObject).getReferenceArray();
            String[] properties = new String[addresses.length];
            for (int i = 0; i < addresses.length; i++)
            {
                if (addresses[i] == 0)
                    continue;
                ObjectReference or = new ObjectReference(snapshot, addresses[i]);
                IObject object = or.getObject();
                properties[i] = object.getClassSpecificName();

            }
            return properties;
        }
        else
        {
            // TODO SoftReferences are not handled, as referents were always
            // null. Log, if otherwise.
            IObject referentObject = (IObject) extraInfoObject.resolveValue("referent"); //$NON-NLS-1$
            if (referentObject != null)
                MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_SoftReferencesNotHandled,
                                Long.toHexString(instance.getObjectAddress())));
            return null;
        }
    }

    private ExtensionPoint extractExtensionPointInfo(IObject instance) throws SnapshotException
    {
        // Expect at least 5 properties
        String[] properties = getExtensionProperties(instance);
        if (properties == null)
            return null;

        Field id = ((IInstance) instance).getField("objectId"); //$NON-NLS-1$
        if (id == null)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_ErrorMsg_ExpectedFieldObjectId,
                            Long.toHexString(instance.getObjectAddress())));
            return null;
        }
        Integer extensionPointId = (Integer) id.getValue();
        ExtensionPoint extensionPoint = new ExtensionPoint(instance.getObjectId(), extensionPointId, properties);

        /*
         * in some heap dumps the the contributorID was a fully qualified name
         * instead of a number. The following lines are an attempt to read the
         * data as number
         */
        Long contributorId = null;
        String contributorIdString = extensionPoint.getContributorId();
        try
        {
            contributorId = Long.valueOf(contributorIdString);
        }
        catch (NumberFormatException e)
        {
            MATPlugin.log(MessageUtil.format(Messages.EquinoxBundleReader_CannotFindContributorID, contributorIdString));
        }
        if (contributorId != null)
        {
            BundleDescriptor contributedBy = bundleDescriptors.get(contributorId);
            extensionPoint.setContributedBy(contributedBy);
        }

        return extensionPoint;
    }

}
