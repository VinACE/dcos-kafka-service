package org.apache.mesos.kafka.scheduler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.mesos.kafka.config.KafkaConfigService;
import org.apache.mesos.kafka.offer.LogOperationRecorder;
import org.apache.mesos.kafka.offer.OfferRequirementProvider;
import org.apache.mesos.kafka.offer.PersistentOperationRecorder;
import org.apache.mesos.kafka.state.KafkaStateService;

import org.apache.mesos.config.ConfigurationService;
import org.apache.mesos.offer.OfferAccepter;
import org.apache.mesos.offer.OfferEvaluator;
import org.apache.mesos.offer.OfferRecommendation;
import org.apache.mesos.offer.OfferRequirement;
import org.apache.mesos.offer.OperationRecorder;

import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.FrameworkInfo;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.SchedulerDriver;

import java.util.Arrays;
import java.util.List;
import java.util.Observable;

/**
 * Kafka Framework Scheduler.
 */
public class KafkaScheduler extends Observable implements org.apache.mesos.Scheduler, Runnable {
  private final Log log = LogFactory.getLog(KafkaScheduler.class);

  private ConfigurationService config;
  private KafkaStateService state;

  private OfferRequirementProvider offerReqProvider;
  private OfferAccepter offerAccepter;

  public KafkaScheduler() {
    config = KafkaConfigService.getConfigService();
    state = KafkaStateService.getStateService();
    addObserver(state);

    offerReqProvider = new OfferRequirementProvider();
    offerAccepter =
      new OfferAccepter(Arrays.asList(
            new LogOperationRecorder(),
            new PersistentOperationRecorder()));
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    log.info("Scheduler driver disconnected");
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    log.error("Scheduler driver error: " + message);
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorID, SlaveID slaveID, int status) {
    log.info("Executor lost: executorId: " + executorID.getValue()
        + " slaveId: " + slaveID.getValue() + " status: " + status);
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorID, SlaveID slaveID,
      byte[] data) {
    log.info("Framework message: executorId: " + executorID.getValue() + " slaveId: "
        + slaveID.getValue() + " data: '" + Arrays.toString(data) + "'");
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    log.info("Offer rescinded: offerId: " + offerId.getValue());
  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
    log.info("Registered framework frameworkId: " + frameworkId.getValue());
    state.setFrameworkId(frameworkId);
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    log.info("Reregistered framework.");
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    log.info(String.format(
        "Received status update for taskId=%s state=%s message='%s'",
        status.getTaskId().getValue(),
        status.getState().toString(),
        status.getMessage()));

    setChanged();
    notifyObservers(status);
  }

  @Override
  public void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    logOffers(offers);
    OfferRequirement offerReq = offerReqProvider.getNextRequirement();
    OfferEvaluator offerEvaluator = new OfferEvaluator(offerReq);
    List<OfferRecommendation> recommendations = offerEvaluator.evaluate(offers);
    List<OfferID> acceptedOffers = offerAccepter.accept(driver, recommendations);

    declineOffers(driver, acceptedOffers, offers);
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    log.info("Slave lost slaveId: " + slaveId.getValue());
  }

  @Override
  public void run() {
    String zkPath = "zk://" + config.get("ZOOKEEPER_ADDR") + "/mesos";
    registerFramework(this, getFrameworkInfo(), zkPath);
  }

  private FrameworkInfo getFrameworkInfo() {
    FrameworkInfo.Builder fwkInfoBuilder = FrameworkInfo.newBuilder()
      .setName(config.get("FRAMEWORK_NAME"))
      .setFailoverTimeout(Double.MAX_VALUE)
      .setUser(config.get("USER"))
      .setRole(config.get("ROLE"))
      .setCheckpoint(true);

    FrameworkID fwkId = state.getFrameworkId();
    if (fwkId != null) {
      fwkInfoBuilder.setId(fwkId);
    }

    return fwkInfoBuilder.build();
  }

  private void logOffers(List<Offer> offers) {
    if (offers == null) {
      return;
    }

    log.info(String.format("Received %d offers", offers.size()));

    for (Offer offer : offers) {
      log.info("Received Offer: " + offer);
    }
  }

  private void declineOffers(SchedulerDriver driver, List<OfferID> acceptedOffers, List<Offer> offers) {
    for (Offer offer : offers) {
      if (!acceptedOffers.contains(offer.getId())) {
        declineOffer(driver, offer);
      }
    }
  }

  private void declineOffers(SchedulerDriver driver, List<Offer> offers) {
    if (offers == null) {
      return;
    }

    for (Offer offer : offers) {
      declineOffer(driver, offer);
    }
  }

  private void declineOffer(SchedulerDriver driver, Offer offer) {
    OfferID offerId = offer.getId();
    log.info(String.format("Scheduler declining offer: %s", offerId));
    driver.declineOffer(offerId);
  }

  private void registerFramework(KafkaScheduler sched, FrameworkInfo frameworkInfo, String masterUri) {
    log.info("Registering without authentication");
    new MesosSchedulerDriver(sched, frameworkInfo, masterUri).run();
  }
}