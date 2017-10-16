package org.interledger.plugin.lpi;

import org.interledger.InterledgerAddress;
import org.interledger.plugin.lpi.QueuedMockLedgerPlugin.AsyncLedgerPluginEventEmitter;
import org.interledger.plugin.lpi.events.ImmutableLedgerPluginConnectedEvent;
import org.interledger.plugin.lpi.events.ImmutableLedgerPluginDisconnectedEvent;
import org.interledger.plugin.lpi.events.ImmutableLedgerPluginErrorEvent;
import org.interledger.plugin.lpi.events.IncomingMessgeRequestEvent;
import org.interledger.plugin.lpi.events.IncomingTransferCancelledEvent;
import org.interledger.plugin.lpi.events.IncomingTransferFulfilledEvent;
import org.interledger.plugin.lpi.events.IncomingTransferPreparedEvent;
import org.interledger.plugin.lpi.events.IncomingTransferRejectedEvent;
import org.interledger.plugin.lpi.events.LedgerPluginConnectedEvent;
import org.interledger.plugin.lpi.events.LedgerPluginDisconnectedEvent;
import org.interledger.plugin.lpi.events.LedgerPluginErrorEvent;
import org.interledger.plugin.lpi.events.OutgoingMessgeRequestEvent;
import org.interledger.plugin.lpi.events.OutgoingTransferCancelledEvent;
import org.interledger.plugin.lpi.events.OutgoingTransferFulfilledEvent;
import org.interledger.plugin.lpi.events.OutgoingTransferPreparedEvent;
import org.interledger.plugin.lpi.events.OutgoingTransferRejectedEvent;
import org.interledger.plugin.lpi.exceptions.LedgerPluginNotConnectedException;
import org.interledger.plugin.lpi.handlers.LedgerPluginEventHandler;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An abstract implementation of a {@link LedgerPlugin} that does directly connects emitted ledger
 * events to proper handlers.
 */
public abstract class AbstractLedgerPlugin<T extends LedgerPluginConfig> implements LedgerPlugin {

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private static final boolean CONNECTED = true;
  private static final boolean DISCONNECTED = false;

  /**
   * A typed representation of the configuration options passed-into this ledger plugin.
   */
  private final T ledgerPluginConfig;

  private final Map<UUID, LedgerPluginEventHandler> ledgerEventHandlers = Maps.newConcurrentMap();

  private LedgerPluginEventEmitter ledgerPluginEventEmitter;

  private AtomicBoolean connected = new AtomicBoolean(false);

  /**
   * Required-args Constructor which utilizes a default {@link LedgerPluginEventEmitter} that
   * synchronously connects to the event handlers.
   *
   * @param configurationOptions A {@link Map} of configuration strings for this plugin.
   */
  protected AbstractLedgerPlugin(final Map<String, String> configurationOptions) {
    Objects.requireNonNull(configurationOptions);
    this.ledgerPluginConfig = toLedgerPluginConfig(
        this.initializeLedgerPlugin(configurationOptions)
    );
    this.ledgerPluginEventEmitter = new SyncLedgerPluginEventEmitter(this.ledgerEventHandlers);
  }

  /**
   * Required-args Constructor.
   *
   * @param configurationOptions     A {@link Map} of configuration strings for this plugin.
   * @param ledgerPluginEventEmitter A {@link LedgerPluginEventEmitter} that is used to emit events
   *                                 from this plugin.
   */
  protected AbstractLedgerPlugin(
      final Map<String, String> configurationOptions,
      final LedgerPluginEventEmitter ledgerPluginEventEmitter
  ) {
    Objects.requireNonNull(configurationOptions);
    this.ledgerPluginConfig = toLedgerPluginConfig(
        this.initializeLedgerPlugin(configurationOptions)
    );
    this.ledgerPluginEventEmitter = Objects.requireNonNull(ledgerPluginEventEmitter);
  }

  @Override
  public InterledgerAddress getConnectorAccount() {
    if (!this.isConnected()) {
      throw new LedgerPluginNotConnectedException(getLedgerPluginConfig().getLedgerPrefix());
    }
    return getLedgerPluginConfig().getConnectorAccount();
  }

  /**
   * Perform any required initialization of this ledger plugin, and return a new options object if
   * required.
   */
  private final Map<String, String> initializeLedgerPlugin(final Map<String, String> options) {
    Objects.requireNonNull(options);

    // Set a ConnectorSecret if none is found...
    final Map<String, String> updatedOptions;
    if (options.get(LedgerPluginConfig.CONNECTOR_SECRET) == null) {
      final byte[] randomBytes = new byte[32];
      ThreadLocalRandom.current().nextBytes(randomBytes);

      final Map<String, String> updatedMap = Maps.newHashMap(options);
      updatedMap.putIfAbsent(LedgerPluginConfig.CONNECTOR_SECRET, new String(randomBytes));

      updatedOptions = ImmutableMap.copyOf(updatedMap);
    } else {
      updatedOptions = options;
    }

    return this.afterInitializeLedgerPlugin(updatedOptions);
  }

  /**
   * Overridable method that allows implementations to perform additional initialization of this
   * plugin.
   */
  protected Map<String, String> afterInitializeLedgerPlugin(
      final Map<String, String> ledgerPluginOptions
  ) {
    Objects.requireNonNull(ledgerPluginOptions);
    return ledgerPluginOptions;
  }

  /**
   * Converts a {@link Map} of configuration key/values strings into an instance of {@link T} (that
   * sub-classes can define) to provide their own typed configuration while conforming to the
   * requirements of this abstract class.
   */
  protected abstract T toLedgerPluginConfig(Map<String, String> options);

  @Override
  public final void connect() {
    if (logger.isDebugEnabled()) {
      logger.debug("connect: {}");
    }

    try {
      if (!this.isConnected()) {
        this.doConnect();
        this.connected.compareAndSet(DISCONNECTED, CONNECTED);
        this.ledgerPluginEventEmitter.emitEvent(ImmutableLedgerPluginConnectedEvent.builder()
            .ledgerPrefix(this.getLedgerInfo().getLedgerPrefix())
            .build());
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);

      // If we can't connect, this will trigger the removal of this ledger plugin.
      this.ledgerPluginEventEmitter.emitEvent(
          ImmutableLedgerPluginErrorEvent.builder()
              .ledgerPrefix(this.getLedgerInfo().getLedgerPrefix())
              .error(e).build()
      );
    }
  }

  public abstract void doConnect();

  @Override
  public final void disconnect() {
    if (logger.isDebugEnabled()) {
      logger.debug("disconnect");
    }

    if (this.isConnected()) {
      this.doDisconnect();
      this.connected.compareAndSet(CONNECTED, DISCONNECTED);
      this.ledgerPluginEventEmitter.emitEvent(ImmutableLedgerPluginDisconnectedEvent.builder()
          .ledgerPrefix(this.getLedgerInfo().getLedgerPrefix())
          .build());
    }
  }

  public abstract void doDisconnect();

  /**
   * Query whether the plugin is currently connected.
   *
   * @return {@code true} if the plugin is connected, {@code false} otherwise.
   */
  @Override
  public boolean isConnected() {
    return this.connected.get();
  }

  @Override
  public LedgerPluginEventEmitter getLedgerPluginEventEmitter() {
    return this.ledgerPluginEventEmitter;
  }

  @Override
  public UUID addLedgerPluginEventHandler(final LedgerPluginEventHandler eventHandler) {
    Objects.requireNonNull(eventHandler);

    final UUID handlerId = UUID.randomUUID();
    this.ledgerEventHandlers.put(handlerId, eventHandler);

    return handlerId;
  }

  @Override
  public void removeLedgerPluginEventHandler(UUID eventHandlerId) {
    this.ledgerEventHandlers.remove(eventHandlerId);
  }

  /**
   * Using HMAC-SHA-256, deterministically generate a UUID from a secret and a public input, which
   * in this case is a ledger-prefix and a transferId.
   *
   * The ID for the next transfer should be deterministically generated, so that the connector
   * doesn't send duplicate outgoing transfers if it receives duplicate notifications.
   *
   * The deterministic generation should ideally be impossible for a third party to predict.
   * Otherwise an attacker might be able to squat on a predicted ID in order to interfere with a
   * payment or make a connector look unreliable. In order to assure this, the connector may use a
   * secret that seeds the deterministic ID generation.
   *
   * @param ledgerPrefix A {@link InterledgerAddress} containing a ledger prefix.
   * @param transferId   A {@link TransferId} that uniquely identifies the transfer.
   *
   * @returns A deterministically generated {@link UUID}.
   **/
  @Override
  public TransferId generateTransferId(
      final InterledgerAddress ledgerPrefix, final TransferId transferId
  ) {
    Objects.requireNonNull(ledgerPrefix);
    LedgerPrefixUtils.assertLedgerPrefix(ledgerPrefix);
    Objects.requireNonNull(transferId);

    final String publicInput = String.format("%s/%s", ledgerPrefix, transferId);

    return Optional.ofNullable(this.getLedgerPluginConfig().getConnectorSecret())
        .map(String::getBytes)
        .map(secretBytes -> {
          try {
            final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(secretBytes);
            byte[] digest = messageDigest.digest(publicInput.getBytes());

            final String hash = BaseEncoding.base16().encode(digest).substring(0, 36);
            final char[] hashCharArray = hash.toCharArray();
            hashCharArray[8] = '-';
            hashCharArray[13] = '-';
            hashCharArray[14] = '4';
            hashCharArray[18] = '-';
            hashCharArray[19] = '8';
            hashCharArray[23] = '-';
            return UUID.fromString(new String(hashCharArray));
          } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
          }
        })
        .map(TransferId::of)
        // If no secret is configured, then throw an exception! In general this should not happen
        // because the connector secret is initialized by default in this implementation, but it's
        // possible that a subclass may have overridden this behavior.
        .orElseThrow(() -> new RuntimeException(
            LedgerPluginConfig.CONNECTOR_SECRET + " is not configured for this connector!"));
  }

  protected T getLedgerPluginConfig() {
    return this.ledgerPluginConfig;
  }

  /**
   * An example {@link LedgerPluginEventEmitter} that allows events to be synchronously emitted into
   * a {@link LedgerPlugin}.
   *
   * For an asynchronous example, consider {@link AsyncLedgerPluginEventEmitter} instead.
   */
  static class SyncLedgerPluginEventEmitter implements LedgerPluginEventEmitter {

    private final Map<UUID, LedgerPluginEventHandler> ledgerEventHandlers;

    public SyncLedgerPluginEventEmitter(
        final Map<UUID, LedgerPluginEventHandler> ledgerEventHandlers
    ) {
      this.ledgerEventHandlers = Objects.requireNonNull(ledgerEventHandlers);
    }

    /////////////////
    // Event Emitters
    /////////////////

    @Override
    public void emitEvent(final LedgerPluginConnectedEvent event) {
      this.ledgerEventHandlers.values().stream().forEach(handler -> handler.onConnect(event));
    }

    @Override
    public void emitEvent(final LedgerPluginDisconnectedEvent event) {
      this.ledgerEventHandlers.values().stream().forEach(handler -> handler.onDisconnect(event));
    }

    @Override
    public void emitEvent(final LedgerPluginErrorEvent event) {
      this.ledgerEventHandlers.values().stream().forEach(handler -> handler.onError(event));
    }

    @Override
    public void emitEvent(final OutgoingTransferPreparedEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferPrepared(event));
    }

    @Override
    public void emitEvent(final OutgoingTransferFulfilledEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferFulfilled(event));
    }

    @Override
    public void emitEvent(final OutgoingTransferRejectedEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferRejected(event));
    }

    @Override
    public void emitEvent(OutgoingTransferCancelledEvent event) {

    }

    @Override
    public void emitEvent(final OutgoingMessgeRequestEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onMessageRequest(event));
    }

    /////////////////////////////
    // Emitted by Implementations
    /////////////////////////////

    @Override
    public void emitEvent(final IncomingTransferPreparedEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferPrepared(event));
    }

    @Override
    public void emitEvent(final IncomingTransferFulfilledEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferFulfilled(event));
    }

    @Override
    public void emitEvent(final IncomingTransferRejectedEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onTransferRejected(event));
    }

    @Override
    public void emitEvent(IncomingTransferCancelledEvent event) {

    }

    @Override
    public void emitEvent(final IncomingMessgeRequestEvent event) {
      this.ledgerEventHandlers.values().stream()
          .forEach(handler -> handler.onMessageRequest(event));
    }
  }
}
