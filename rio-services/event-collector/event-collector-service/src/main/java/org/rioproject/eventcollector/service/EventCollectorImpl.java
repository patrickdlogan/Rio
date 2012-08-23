/*
 * Copyright to the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rioproject.eventcollector.service;

import com.sun.jini.config.Config;
import com.sun.jini.landlord.*;
import com.sun.jini.start.LifeCycle;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.event.RemoteEvent;
import net.jini.core.event.RemoteEventListener;
import net.jini.core.event.UnknownEventException;
import net.jini.core.lease.Lease;
import net.jini.core.lease.LeaseDeniedException;
import net.jini.core.lease.UnknownLeaseException;
import net.jini.export.Exporter;
import net.jini.id.Uuid;
import net.jini.id.UuidFactory;
import net.jini.security.BasicProxyPreparer;
import net.jini.security.ProxyPreparer;
import org.rioproject.core.jsb.ServiceBeanContext;
import org.rioproject.event.EventDescriptor;
import org.rioproject.event.EventDescriptorFactory;
import org.rioproject.eventcollector.api.EventCollectorRegistration;
import org.rioproject.eventcollector.api.UnknownEventCollectorRegistration;
import org.rioproject.eventcollector.proxy.EventCollectorBackend;
import org.rioproject.eventcollector.proxy.Registration;
import org.rioproject.jsb.ServiceBeanActivation;
import org.rioproject.jsb.ServiceBeanAdapter;
import org.rioproject.log.ServiceLogEvent;
import org.rioproject.resources.servicecore.LeaseListener;
import org.rioproject.resources.servicecore.LeaseListenerAdapter;
import org.rioproject.resources.util.ThrowableUtil;
import org.rioproject.sla.SLAThresholdEvent;
import org.rioproject.util.BannerProvider;
import org.rioproject.util.BannerProviderImpl;
import org.rioproject.util.TimeConstants;

import java.io.File;
import java.io.IOException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service implementation of the {@code EventCollector}.
 *
 * @author Dennis Reedy
 */
public class EventCollectorImpl extends ServiceBeanAdapter implements EventCollectorBackend {
    private RegistrationManager registrationManager;
    private LeaseFactory leaseFactory;
    private ProxyPreparer listenerPreparer;
    private final Map<Uuid, RegisteredNotification> registrations = new HashMap<Uuid, RegisteredNotification>();
    private final ExecutorService execService = Executors.newSingleThreadExecutor();
    private LeasePeriodPolicy leasePolicy;
    private final LeaseListener leaseListener = new LeaseMonitor();
    private final LocalLandlord localLandlord = new LocalLandlordAdaptor();
    private EventManager eventManager;
    private EventCollectorBackend eventCollectorBackendRef;
    private ScheduledExecutorService leaseReaperScheduler;
    private static final BlockingQueue<RemoteEvent> eventQ = new LinkedBlockingQueue<RemoteEvent>();
    private static final String CONFIG_COMPONENT = EventCollectorImpl.class.getPackage().getName();
    private static final Logger logger = Logger.getLogger(EventCollectorImpl.class.getName());

    /**
     * Simple constructor used if the {@code EventCollectorImpl} is created as a dynamic service.
     */
    @SuppressWarnings("unused")
    public EventCollectorImpl() {
        super();
    }

    /**
     * Create an {@code EventCollectorImpl} launched from the ServiceStarter framework
     *
     * @param configArgs Configuration arguments
     * @param lifeCycle Service lifecycle manager
     *
     * @throws Exception If the {@code EventCollectorImpl} cannot be created
     */
    @SuppressWarnings("unused")
    public EventCollectorImpl(String[] configArgs, LifeCycle lifeCycle) throws Exception {
        super();
        bootstrap(configArgs);
    }

    private void bootstrap(String[] configArgs) throws Exception {
        ServiceBeanContext context = ServiceBeanActivation.getServiceBeanContext(CONFIG_COMPONENT,
                                                                                 "Event Collector",
                                                                                 configArgs,
                                                                                 getClass().getClassLoader());
        BannerProvider bannerProvider =
            (BannerProvider)context.getConfiguration().getEntry(CONFIG_COMPONENT,
                                                                "bannerProvider",
                                                                BannerProvider.class,
                                                                new BannerProviderImpl());
        logger.info(bannerProvider.getBanner(context.getServiceElement().getName()));
        try {
            start(context);
            ServiceBeanActivation.LifeCycleManager lMgr =
                (ServiceBeanActivation.LifeCycleManager)context.getServiceBeanManager().getDiscardManager();
            lMgr.register(getServiceProxy(), context);
        } catch(Exception e) {
            logger.log(Level.SEVERE, "Register to LifeCycleManager", e);
            throw e;
        }
    }

    @Override
    public void initialize(final ServiceBeanContext context) throws Exception {
        super.initialize(context);
        final Configuration config = getConfiguration();
        /* 5 minute default Lease time */
        final long DEFAULT_LEASE_TIME = TimeConstants.FIVE_MINUTES;
        /* 1 day max lease time */
        final long DEFAULT_MAX_LEASE_TIME = TimeConstants.ONE_DAY;
        /* Get the Lease policy */
        leasePolicy = (LeasePeriodPolicy) Config.getNonNullEntry(config,
                                                                 CONFIG_COMPONENT,
                                                                 "leasePeriodPolicy",
                                                                 LeasePeriodPolicy.class,
                                                                 new FixedLeasePeriodPolicy(DEFAULT_MAX_LEASE_TIME,
                                                                                            DEFAULT_LEASE_TIME));

        /* Get the reaping interval */
        long reapingInterval;
        try {
            reapingInterval = Config.getLongEntry(config,
                                                  CONFIG_COMPONENT,
                                                  "reapingInterval",
                                                  TimeConstants.ONE_SECOND*10,
                                                  1,
                                                  Long.MAX_VALUE);

        } catch (ConfigurationException e) {
            logger.log(Level.WARNING, "Getting reapingInterval", e);
            reapingInterval = TimeConstants.ONE_SECOND*10;
        }

        if(logger.isLoggable(Level.CONFIG))
            logger.config(String.format("Reaping interval set to %d", reapingInterval));
        leaseReaperScheduler = Executors.newSingleThreadScheduledExecutor();
        leaseReaperScheduler.scheduleAtFixedRate(new RegistrationLeaseReaper(), 0, reapingInterval, TimeUnit.MILLISECONDS);

        final StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append(System.getProperty("user.home")).append(File.separator).append(".rio");
        pathBuilder.append(File.separator).append("events");

        final File defaultPersistentDirectoryRoot = new File(pathBuilder.toString());
        File persistentDirectoryRoot;
        try {
            persistentDirectoryRoot = (File) config.getEntry(EventCollectorImpl.class.getPackage().getName(),
                                                             "persistentDirectoryRoot",
                                                             File.class,
                                                             defaultPersistentDirectoryRoot);
        } catch (ConfigurationException e) {
            persistentDirectoryRoot = defaultPersistentDirectoryRoot;
        }

        /* Get the ProxyPreparer for ServiceInstantiator instances */
        listenerPreparer = (ProxyPreparer)config.getEntry(CONFIG_COMPONENT,
                                                          "instantiatorPreparer",
                                                          ProxyPreparer.class,
                                                          new BasicProxyPreparer());

        eventManager = (EventManager) config.getEntry(CONFIG_COMPONENT,
                                                      "eventManager",
                                                      EventManager.class,
                                                      new PersistentEventManager());
        /*new TransientEventManager());*/
        execService.submit(new EventNotifier());


        final List<EventDescriptor> descList = new ArrayList<EventDescriptor>();
        descList.addAll(EventDescriptorFactory.createEventDescriptors("org.rioproject.monitor:monitor-api:5.0-SNAPSHOT",
                                                                      "org.rioproject.monitor.ProvisionFailureEvent",
                                                                      "org.rioproject.monitor.ProvisionMonitorEvent"));
        descList.add(SLAThresholdEvent.getEventDescriptor());
        descList.add(ServiceLogEvent.getEventDescriptor());

        final EventDescriptor[] eventDescriptors =
            (EventDescriptor[]) config.getEntry(CONFIG_COMPONENT,
                                                "eventDescriptors",
                                                EventDescriptor[].class,
                                                descList.toArray(new EventDescriptor[descList.size()]));

        final EventCollectorContext eventCollectorContext = new EventCollectorContext(config,
                                                                                      eventQ,
                                                                                      eventDescriptors,
                                                                                      context.getDiscoveryManagement(),
                                                                                      persistentDirectoryRoot);

        //Uuid serviceUuid = UuidFactory.create(serviceID.getMostSignificantBits(), serviceID.getLeastSignificantBits());
        final Uuid serviceUuid = UuidFactory.generate();
        leaseFactory = new LeaseFactory(eventCollectorBackendRef, serviceUuid);
        registrationManager = new RegistrationManager(eventCollectorContext);
        for(RegisteredNotification registeredNotification : registrationManager.getRegisteredNotifications(listenerPreparer)) {
            registrations.put(registeredNotification.getCookie(), registeredNotification);
        }
        eventManager.initialize(eventCollectorContext);
        final StringBuilder groups = new StringBuilder();
        for(String group : context.getServiceBeanConfig().getGroups()) {
            if(groups.length()>0)
                groups.append(", ");
            groups.append(group);
        }
        logger.info(getServiceBeanContext().getServiceElement().getName()+": started ["+groups.toString()+"]");
    }

    @Override
    protected Remote exportDo(Exporter exporter) throws Exception {
        if(exporter==null)
            throw new IllegalArgumentException("exporter is null");
        if(eventCollectorBackendRef==null) {
            eventCollectorBackendRef = (EventCollectorBackend) exporter.export(this);
        }
        return(eventCollectorBackendRef);
    }

    @Override
    public void destroy() {
        logger.info(getServiceBeanContext().getServiceElement().getName()+": destroy() notification");
        for(Map.Entry<Uuid, RegisteredNotification> entry : registrations.entrySet()) {
            RegisteredNotification registeredNotification = entry.getValue();
            if(registeredNotification.getEventListener()!=null) {
                registeredNotification.setEventIndex(eventManager.getIndex());
                registrationManager.update(registeredNotification);
            }
        }
        if(execService!=null) {
            execService.shutdownNow();
        }
        if(eventManager!=null) {
            eventManager.terminate();
        }
        if(leaseReaperScheduler!=null) {
            leaseReaperScheduler.shutdownNow();
        }
        super.destroy();
    }

    public EventCollectorRegistration register(final long duration) throws IOException, LeaseDeniedException {
        if (duration < 1 && duration != Lease.ANY)
            throw new IllegalArgumentException("Duration must be a positive value");
        Uuid registrationID = UuidFactory.generate();
        RegisteredNotification registeredNotification = new RegisteredNotification(registrationID);

        LeasePeriodPolicy.Result leasePeriod = leasePolicy.grant(registeredNotification, duration);
        Lease lease = leaseFactory.newLease(registeredNotification.getCookie(), leasePeriod.expiration);
        registeredNotification.setExpiration(leasePeriod.expiration);

        if(logger.isLoggable(Level.FINE)) {
            DateFormat formatter = new SimpleDateFormat("HH:mm:ss,SSS");
            long t1 = System.currentTimeMillis();
            long actual = lease.getExpiration()-t1;
            logger.fine(String.format("Lease duration requested: %d, granted, expires %s, actual: %d",
                                      duration, formatter.format(new Date(lease.getExpiration())), actual));
        }

        synchronized (registrations) {
            registrations.put(registrationID, registeredNotification);
        }
        return new Registration((EventCollectorBackend)getServiceProxy(), registrationID, lease);
    }

    public void enableDelivery(final Uuid uuid, final RemoteEventListener remoteEventListener)
        throws UnknownEventCollectorRegistration, IOException {
        if (remoteEventListener == null) {
            disableDelivery(uuid);
        } else {
            RemoteEventListener preparedListener = (RemoteEventListener) listenerPreparer.prepareProxy(remoteEventListener);
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "prepared listener: {0}", preparedListener);
            }
            RegisteredNotification registeredNotification = registrations.get(uuid);
            if(registeredNotification!=null) {
                registeredNotification.setRemoteEventListener(preparedListener);
                eventManager.historyNotification(registeredNotification);
            } else {
                logger.warning("Unable to enable delivery for unknown registration ID");
                throw new UnknownEventCollectorRegistration("Unable to enable delivery for unknown registration ID");
            }
        }
    }

    public void disableDelivery(Uuid uuid) throws UnknownEventCollectorRegistration, IOException {
        RegisteredNotification registeredNotification = registrations.get(uuid);
        if(registeredNotification!=null) {
            registeredNotification.setRemoteEventListener(null);
            registeredNotification.setEventIndex(eventManager.getIndex());
        } else {
            logger.warning("Unable to disable delivery for unknown registration ID");
            throw new UnknownEventCollectorRegistration("Unable to disable delivery for unknown registration ID");
        }
    }

    public long renew(Uuid uuid, long duration) throws LeaseDeniedException, UnknownLeaseException {
        return renewDo(uuid, duration);
    }

    public void cancel(Uuid uuid) throws UnknownLeaseException {
        cancelDo(uuid);
    }

    public Landlord.RenewResults renewAll(Uuid[] uuids, long[] extensions)  {
        return LandlordUtil.renewAll(localLandlord, uuids, extensions);
    }

    public Map cancelAll(Uuid[] uuids) {
        for(Uuid uuid : uuids) {
            RegisteredNotification registeredNotification = registrations.remove(uuid);
            if(registeredNotification!=null) {
                leaseListener.removed(registeredNotification);
            }
        }
        return LandlordUtil.cancelAll(localLandlord, uuids);
    }

    private long renewDo(Uuid uuid, long duration) throws LeaseDeniedException, UnknownLeaseException {
        RegisteredNotification registeredNotification = registrations.get(uuid);
        LeasePeriodPolicy.Result result;
        if(registeredNotification!=null) {
            result = leasePolicy.renew(registeredNotification, duration);
            registeredNotification.setExpiration(result.expiration);
            registrations.put(uuid, registeredNotification);
            leaseListener.renewed(registeredNotification);
        } else {
            throw new UnknownLeaseException("Unable to renew unknown lease");
        }
        return result.duration;
    }

    private void cancelDo(Uuid uuid) throws UnknownLeaseException {
        RegisteredNotification registeredNotification = registrations.get(uuid);
        if(registeredNotification!=null) {
            registrations.remove(uuid);
            leaseListener.removed(registeredNotification);
        } else {
            throw new UnknownLeaseException("Unable to cancel unknown lease");
        }
    }

    /**
     * Handles notifying registered consumers.
     */
    class EventNotifier implements Runnable {

        public void run() {
            while (true) {
                RemoteEvent event;
                try {
                    event = eventQ.take();
                } catch (InterruptedException e) {
                    logger.info("EventWorker breaking out of main loop");
                    break;
                }
                List<Uuid> removals = new ArrayList<Uuid>();
                if(event!=null) {
                    for(Map.Entry<Uuid, RegisteredNotification> entry : registrations.entrySet()) {
                        if(entry.getValue().getEventListener()!=null) {
                            RegisteredNotification registeredNotification = entry.getValue();
                            if(!registeredNotification.getHistoryUpdating()) {
                                try {
                                    registeredNotification.getEventListener().notify(event);
                                } catch (UnknownEventException e) {
                                    logger.log(Level.WARNING, "UnknownEventException return from listener", e);
                                } catch (RemoteException e) {
                                    if(!ThrowableUtil.isRetryable(e)) {
                                        if(logger.isLoggable(Level.FINEST)) {
                                            logger.log(Level.WARNING,
                                                       "Unrecoverable RemoteException returned from listener", e);
                                        } else {
                                            logger.warning(String.format("Unrecoverable RemoteException returned from listener: %s",
                                                                         e.getMessage()));
                                        }
                                        try {
                                            removals.add(entry.getKey());
                                            cancelDo(entry.getValue().getCookie());
                                        } catch (Exception ex) {
                                            if (logger.isLoggable(Level.FINEST))
                                                logger.log(Level.WARNING,
                                                           "Removing resource and cancelling Lease",
                                                           e);
                                        }
                                    }
                                }
                            } else {
                                registeredNotification.addMissedEvent(event);
                            }
                        }
                    }

                    /* Remove registrations if notifications have failed */
                    for(Uuid uuid : removals) {
                        synchronized (registrations) {
                            registrations.remove(uuid);
                        }
                    }
                }
            }
        }
    }

    /*
     * Added for test access.
     */
    int getRegistrationSize() {
        return registrations.size();
    }

    /**
     * Adaptor class implementation of LocalLandlord. We use this adaptor class with LandlordUtil.
     */
    private class LocalLandlordAdaptor implements LocalLandlord {

        public long renew(Uuid cookie, long extension) throws LeaseDeniedException, UnknownLeaseException {
            return renewDo(cookie, extension);
        }

        public void cancel(Uuid cookie) throws UnknownLeaseException {
            cancelDo(cookie);
        }
    }

    /**
     * Listens for lease transition notifications.
     */
    class LeaseMonitor extends LeaseListenerAdapter {
        @Override
        public void register(final LeasedResource resource) {
            logger.info(String.format("Added registration, count now [%d]", registrations.size()));
            registrationManager.persist((RegisteredNotification)resource);
        }

        @Override
        public void expired(final LeasedResource resource) {
            logger.info(String.format("Expired registration, count now [%d]", registrations.size()));
            registrationManager.remove((RegisteredNotification)resource);
        }

        @Override
        public void removed(final LeasedResource resource) {
            logger.info(String.format("Removed registration, count now [%d]", registrations.size()));
            registrationManager.remove((RegisteredNotification)resource);
        }

        @Override
        public void renewed(LeasedResource resource) {
            registrationManager.update((RegisteredNotification)resource);
        }
    }

    /**
     * Periodically checks for lease validity.
     */
    class RegistrationLeaseReaper implements Runnable {

        public void run() {
            Set<Map.Entry<Uuid, RegisteredNotification>> mapEntries;
                synchronized (registrations) {
                    mapEntries = registrations.entrySet();
                }
                for (Map.Entry<Uuid, RegisteredNotification> entry : mapEntries) {
                    RegisteredNotification registeredNotification = entry.getValue();
                    if (!ensure(registeredNotification)) {
                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE,
                                       "Lease expired for resource, cookie {0}",
                                       new Object[]{registeredNotification.getCookie()});
                        }
                        registrations.remove(entry.getKey());
                        leaseListener.removed(registeredNotification);
                    }
                }

        }

        private boolean ensure(final LeasedResource resource) {
            return(resource.getExpiration() > System.currentTimeMillis());
        }
    }

}
