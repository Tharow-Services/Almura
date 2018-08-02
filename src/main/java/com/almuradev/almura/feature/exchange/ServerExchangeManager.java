/*
 * This file is part of Almura.
 *
 * Copyright (c) AlmuraDev <https://github.com/AlmuraDev/>
 *
 * All Rights Reserved.
 */
package com.almuradev.almura.feature.exchange;

import static com.google.common.base.Preconditions.checkNotNull;

import com.almuradev.almura.Almura;
import com.almuradev.almura.feature.exchange.database.ExchangeQueries;
import com.almuradev.almura.feature.exchange.network.ClientboundExchangeGuiResponsePacket;
import com.almuradev.almura.feature.exchange.network.ClientboundExchangeRegistryPacket;
import com.almuradev.almura.feature.notification.ServerNotificationManager;
import com.almuradev.almura.shared.database.DatabaseManager;
import com.almuradev.almura.shared.database.DatabaseUtils;
import com.almuradev.almura.shared.network.NetworkConfig;
import com.almuradev.core.event.Witness;
import com.almuradev.generated.axs.tables.Axs;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Results;
import org.slf4j.Logger;
import org.spongepowered.api.GameState;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.game.state.GameStartingServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.network.ChannelBinding;
import org.spongepowered.api.network.ChannelId;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.text.Text;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ServerExchangeManager extends Witness.Impl implements Witness.Lifecycle {

    private final PluginContainer container;
    private final Scheduler scheduler;
    private final Logger logger;
    private final ChannelBinding.IndexedMessageChannel network;
    private final DatabaseManager databaseManager;
    private final ServerNotificationManager notificationManager;

    private final Map<String, Exchange> exchanges = new HashMap<>();

    @Inject
    public ServerExchangeManager(final PluginContainer container, final Scheduler scheduler, final Logger logger, final @ChannelId(NetworkConfig
        .CHANNEL) ChannelBinding.IndexedMessageChannel network, final DatabaseManager databaseManager,
        final ServerNotificationManager notificationManager) {
        this.container = container;
        this.scheduler = scheduler;
        this.logger = logger;
        this.network = network;
        this.databaseManager = databaseManager;
        this.notificationManager = notificationManager;
    }

    @Override
    public boolean lifecycleSubscribable(final GameState state) {
        return state == GameState.SERVER_STARTING;
    }

    @Listener
    public void onServerStarting(final GameStartingServerEvent event) {
        this.scheduler
            .createTaskBuilder()
            .async()
            .execute(this::loadExchanges)
            .submit(this.container);
    }

    @Listener
    public void onClientConnectionEventJoin(final ClientConnectionEvent.Join event, @Getter("getTargetEntity") final Player player) {

        // Send exchanges to joiner
        this.network.sendTo(player, new ClientboundExchangeRegistryPacket(
                this.exchanges
                    .values()
                    .stream()
                    .filter(axs -> {
                        if (!axs.isHidden()) {
                            return true;
                        }

                        return player.hasPermission(Almura.ID + ".exchange.admin");
                    })
                    .collect(Collectors.toSet())
            )
        );
    }

    public Optional<Exchange> getExchange(final String id) {
        checkNotNull(id);

        return Optional.ofNullable(this.exchanges.get(id));
    }

    public void loadExchanges() {

        this.logger.info("Querying database for exchanges, please wait...");

        final Map<String, Exchange> exchanges = new HashMap<>();

        try (final DSLContext context = this.databaseManager.createContext(true)) {
            final Results results = ExchangeQueries
                .createFetchAllExchanges()
                .build(context)
                .keepStatement(false)
                .fetchMany();

            results.forEach(result -> {
                for (Record record : result) {
                    final String id = record.getValue(Axs.AXS.ID);
                    final Timestamp created = record.getValue(Axs.AXS.CREATED);
                    final UUID creator = DatabaseUtils.uniqueIdFromBytes(record.getValue(Axs.AXS.CREATOR));
                    final String name = record.getValue(Axs.AXS.NAME);
                    final String permission = record.getValue(Axs.AXS.PERMISSION);
                    final boolean isHidden = record.getValue(Axs.AXS.IS_HIDDEN);

                    exchanges.put(id, new BasicExchange(id, created.toInstant(), creator, name, permission, isHidden));
                }
            });
        } catch (SQLException e) {
            e.printStackTrace();
        }

        this.scheduler
            .createTaskBuilder()
            .execute(() -> {
                this.exchanges.clear();

                this.exchanges.putAll(exchanges);

                if (this.exchanges.isEmpty()) {
                    // TODO Begin Test Code
                    // TODO I might automatically create the Global Exchange (almura.global) by default (if no others are found when loading from db)
                    final BasicExchange exchange = new BasicExchange("almura.global", Instant.now(), UUID.randomUUID(), "Exchange",
                        "almura.exchange.global", false);
                    this.exchanges.put("almura.global", exchange);

                    // Yes, I am purposely running this sync
                    try (final DSLContext context1 = this.databaseManager.createContext(true)) {
                        final int result = ExchangeQueries
                            .createInsertExchange(exchange.getCreator(), exchange.getId(), exchange.getName(), exchange.getPermission(), exchange
                                .isHidden())
                            .build(context1)
                            .keepStatement(false)
                            .execute();

                        if (result == 0) {
                            Thread.dumpStack();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }

                this.logger.info("Loaded {} exchange(s).", this.exchanges.size());

                // Re-send exchanges to everyone
                this.network.sendToAll(new ClientboundExchangeRegistryPacket(this.exchanges.isEmpty() ? null : new HashSet<>(this.exchanges.values
                    ())));
            })
            .submit(this.container);
    }

    public void handleExchangeManage(final Player player) {
        if (!player.hasPermission(Almura.ID + ".exchange.manage")) {
            // TODO Dockter
            return;
        }

        this.network.sendTo(player, new ClientboundExchangeGuiResponsePacket(ExchangeGuiType.MANAGE, null));
    }

    public void handleExchangeSpecific(final Player player, final String id) {
        checkNotNull(player);
        checkNotNull(id);

        if (!player.hasPermission(Almura.ID + ".exchange.open")) {
            // TODO Dockter
            return;
        }

        final Exchange exchange = this.getExchange(id).orElse(null);

        if (exchange == null || !player.hasPermission(exchange.getPermission())) {
            // TODO Dockter

            this.network.sendTo(player, new ClientboundExchangeRegistryPacket(
                this.exchanges
                    .values()
                    .stream()
                    .filter(axs -> {
                        if (!axs.isHidden()) {
                            return true;
                        }

                        return player.hasPermission(Almura.ID + ".exchange.admin");
                    })
                    .collect(Collectors.toSet()))
            );
        } else {
            this.network.sendTo(player, new ClientboundExchangeGuiResponsePacket(ExchangeGuiType.SPECIFIC, id));
        }
    }

    public void handleExchangeAdd(final Player player, final String id, final String name, final String permission, final boolean isHidden) {
        checkNotNull(player);
        checkNotNull(id);
        checkNotNull(name);
        checkNotNull(permission);

        if (!player.hasPermission(Almura.ID + ".exchange.create")) {
            this.notificationManager.sendPopupNotification(player, Text.of("Exchange Manager"), Text.of("Insufficient Permission!, "
                + "Exchange addition failed."), 5);
            return;
        }

        if (this.getExchange(id).isPresent()) {
            this.notificationManager.sendPopupNotification(player, Text.of("Exchange Manager"), Text.of("This Exchange already "
                + "exists!"), 5);

            this.network.sendTo(player, new ClientboundExchangeRegistryPacket(
                    this.exchanges
                        .values()
                        .stream()
                        .filter(axs -> {
                            if (!axs.isHidden()) {
                                return true;
                            }

                            return player.hasPermission(Almura.ID + ".exchange.admin");
                        })
                        .collect(Collectors.toSet())
                )
            );
        } else {
            this.scheduler
                .createTaskBuilder()
                .async()
                .execute(() -> {
                    try (final DSLContext context = this.databaseManager.createContext(true)) {
                        final int result = ExchangeQueries
                            .createInsertExchange(player.getUniqueId(), id, name, permission, isHidden)
                            .build(context)
                            .keepStatement(false)
                            .execute();

                        if (result == 0) {
                            this.scheduler
                                .createTaskBuilder()
                                .execute(() -> this.notificationManager.sendPopupNotification(player, Text.of("Exchange Manager"), Text.of(
                                    "Thread execution to add Exchange to database failed!"), 5))
                                .submit(this.container);
                        } else {
                            this.loadExchanges();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                })
                .submit(this.container);
        }
    }

    public void handleExchangeModify(final Player player, final String id, final String name, final String permission, final boolean isHidden) {
        checkNotNull(player);
        checkNotNull(id);
        checkNotNull(name);
        checkNotNull(permission);

        if (!player.hasPermission(Almura.ID + ".exchange.modify")) {
            // TODO Dockter, handle this
            return;
        }

        final Exchange axs = this.getExchange(id).orElse(null);

        if (axs == null) {
            // TODO Dockter, we're in a desync...either send them a notification that modify failed as it doesn't exist or remove this TODO

            this.network.sendTo(player, new ClientboundExchangeRegistryPacket(
                    this.exchanges
                        .values()
                        .stream()
                        .filter(a -> {
                            if (!a.isHidden()) {
                                return true;
                            }

                            return player.hasPermission(Almura.ID + ".axs.admin");
                        })
                        .collect(Collectors.toSet())
                )
            );
        } else {
            this.scheduler.createTaskBuilder()
                .async()
                .execute(() -> {
                    try (final DSLContext context = this.databaseManager.createContext(true)) {
                        final int result = ExchangeQueries
                            .createUpdateExchange(id, name, permission, isHidden)
                            .build(context)
                            .keepStatement(false)
                            .execute();

                        if (result == 0) {
                            this.scheduler
                                .createTaskBuilder()
                                .execute(() -> this.notificationManager.sendPopupNotification(player, Text.of("Exchange Manager"), Text.of(
                                    "Thread execution to modify Exchange in database failed!"), 5))
                                .submit(this.container);
                        } else {
                            this.loadExchanges();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                })
                .submit(this.container);
        }
    }

    public void handleExchangeDelete(final Player player, final String id) {
        checkNotNull(player);
        checkNotNull(id);

        if (!this.getExchange(id).isPresent()) {
            // TODO Dockter, we're in a desync...either send them a notification that deletion failed as it doesn't exist or remove this TODO
            this.network.sendTo(player, new ClientboundExchangeRegistryPacket(
                    this.exchanges
                        .values()
                        .stream()
                        .filter(axs -> {
                            if (!axs.isHidden()) {
                                return true;
                            }

                            return player.hasPermission(Almura.ID + ".exchange.admin");
                        })
                        .collect(Collectors.toSet())
                )
            );
        } else {
            this.scheduler
                .createTaskBuilder()
                .async()
                .execute(() -> {
                    try (final DSLContext context = this.databaseManager.createContext(true)) {
                        final int result = ExchangeQueries
                            .createDeleteExchange(id)
                            .build(context)
                            .keepStatement(false)
                            .execute();

                        if (result == 0) {
                            this.scheduler
                                .createTaskBuilder()
                                .execute(() -> this.notificationManager.sendPopupNotification(player, Text.of("Exchange Manager"), Text.of(
                                    "Thread execution to delete Exchange from database failed!"), 5))
                                .submit(this.container);
                        } else {
                            this.loadExchanges();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                })
                .submit(this.container);
        }
    }
}