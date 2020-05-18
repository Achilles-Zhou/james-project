/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.webadmin.data.jmap;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.Username;
import org.apache.james.jmap.api.projections.MessageFastViewPrecomputedProperties;
import org.apache.james.jmap.api.projections.MessageFastViewProjection;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.MailboxMetaData;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.apache.james.task.Task;
import org.apache.james.task.Task.Result;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.util.streams.Iterators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

public class MessageFastViewProjectionCorrector {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageFastViewProjectionCorrector.class);
    
    private static final Duration DELAY = Duration.ZERO;
    private static final Duration PERIOD = Duration.ofSeconds(1);

    public static class RunningOptions {
        public static RunningOptions withMessageRatePerSecond(int messageRatePerSecond) {
            return new RunningOptions(messageRatePerSecond);
        }

        public static RunningOptions DEFAULT = new RunningOptions(10);

        private final int messageRatePerSecond;

        public RunningOptions(int messageRatePerSecond) {
            Preconditions.checkArgument(messageRatePerSecond > 0, "'messageParallelism' must be strictly positive");

            this.messageRatePerSecond = messageRatePerSecond;
        }

        public int getMessageRatePerSecond() {
            return messageRatePerSecond;
        }
    }

    private static class ProjectionEntry {
        private final MessageManager messageManager;
        private final MessageUid uid;
        private final MailboxSession session;

        private ProjectionEntry(MessageManager messageManager, MessageUid uid, MailboxSession session) {
            this.messageManager = messageManager;
            this.uid = uid;
            this.session = session;
        }

        private MessageManager getMessageManager() {
            return messageManager;
        }

        private MessageUid getUid() {
            return uid;
        }

        private MailboxSession getSession() {
            return session;
        }
    }

    static class Progress {
        private final AtomicLong processedUserCount;
        private final AtomicLong processedMessageCount;
        private final AtomicLong failedUserCount;
        private final AtomicLong failedMessageCount;

        Progress() {
            failedUserCount = new AtomicLong();
            processedMessageCount = new AtomicLong();
            processedUserCount = new AtomicLong();
            failedMessageCount = new AtomicLong();
        }

        long getProcessedUserCount() {
            return processedUserCount.get();
        }

        long getProcessedMessageCount() {
            return processedMessageCount.get();
        }

        long getFailedUserCount() {
            return failedUserCount.get();
        }

        long getFailedMessageCount() {
            return failedMessageCount.get();
        }
    }

    private final UsersRepository usersRepository;
    private final MailboxManager mailboxManager;
    private final MessageFastViewProjection messageFastViewProjection;
    private final MessageFastViewPrecomputedProperties.Factory projectionItemFactory;

    @Inject
    MessageFastViewProjectionCorrector(UsersRepository usersRepository, MailboxManager mailboxManager,
                                       MessageFastViewProjection messageFastViewProjection,
                                       MessageFastViewPrecomputedProperties.Factory projectionItemFactory) {
        this.usersRepository = usersRepository;
        this.mailboxManager = mailboxManager;
        this.messageFastViewProjection = messageFastViewProjection;
        this.projectionItemFactory = projectionItemFactory;
    }

    Mono<Result> correctAllProjectionItems(Progress progress, RunningOptions runningOptions) {
        return correctProjection(listAllMailboxMessages(progress), runningOptions, progress);
    }

    Mono<Result> correctUsersProjectionItems(Progress progress, Username username, RunningOptions runningOptions) {
        MailboxSession session = mailboxManager.createSystemSession(username);
        return correctProjection(listUserMailboxMessages(progress, session), runningOptions, progress);
    }

    private Flux<ProjectionEntry> listAllMailboxMessages(Progress progress) {
        try {
            return Iterators.toFlux(usersRepository.list())
                .map(mailboxManager::createSystemSession)
                .doOnNext(any -> progress.processedUserCount.incrementAndGet())
                .flatMap(session -> listUserMailboxMessages(progress, session));
        } catch (UsersRepositoryException e) {
            return Flux.error(e);
        }
    }

    private Flux<ProjectionEntry> listUserMailboxMessages(Progress progress, MailboxSession session) {
        try {
            return listUsersMailboxes(session)
                .flatMap(mailboxMetadata -> retrieveMailbox(session, mailboxMetadata))
                .flatMap(Throwing.function(messageManager -> listAllMailboxMessages(messageManager, session)
                    .map(message -> new ProjectionEntry(messageManager, message.getUid(), session))));
        } catch (MailboxException e) {
            LOGGER.error("JMAP fastview re-computation aborted for {} as we failed listing user mailboxes", session.getUser(), e);
            progress.failedUserCount.incrementAndGet();
            return Flux.empty();
        }
    }

    private Mono<Result> correctProjection(ProjectionEntry entry, Progress progress) {
        return retrieveContent(entry.getMessageManager(), entry.getSession(), entry.getUid())
            .map(this::computeProjectionEntry)
            .flatMap(this::storeProjectionEntry)
            .doOnSuccess(any -> progress.processedMessageCount.incrementAndGet())
            .thenReturn(Result.COMPLETED)
            .onErrorResume(e -> {
                LOGGER.error("JMAP fastview re-computation aborted for {} - {} - {}",
                    entry.getSession().getUser(),
                    entry.getMessageManager().getId(),
                    entry.getUid(), e);
                progress.failedMessageCount.incrementAndGet();
                return Mono.just(Result.PARTIAL);
            });
    }

    private Mono<Result> correctProjection(Flux<ProjectionEntry> entries, RunningOptions runningOptions, Progress progress) {
        return throttleWithRate(entries, runningOptions)
            .flatMap(entry -> correctProjection(entry, progress))
            .reduce(Task::combine)
            .switchIfEmpty(Mono.just(Result.COMPLETED));
    }

    private Flux<ProjectionEntry> throttleWithRate(Flux<ProjectionEntry> entries, RunningOptions runningOptions) {
        return entries.windowTimeout(runningOptions.getMessageRatePerSecond(), Duration.ofSeconds(1))
            .zipWith(Flux.interval(DELAY, PERIOD))
            .flatMap(Tuple2::getT1);
    }

    private Flux<MailboxMetaData> listUsersMailboxes(MailboxSession session) throws MailboxException {
        return Flux.fromIterable(mailboxManager.search(MailboxQuery.privateMailboxesBuilder(session).build(), session));
    }

    private Mono<MessageManager> retrieveMailbox(MailboxSession session, MailboxMetaData mailboxMetadata) {
        return Mono.fromCallable(() -> mailboxManager.getMailbox(mailboxMetadata.getId(), session));
    }

    private Flux<MessageResult> listAllMailboxMessages(MessageManager messageManager, MailboxSession session) throws MailboxException {
        return Iterators.toFlux(messageManager.getMessages(MessageRange.all(), FetchGroup.MINIMAL, session));
    }

    private Mono<MessageResult> retrieveContent(MessageManager messageManager, MailboxSession session, MessageUid uid) {
        try {
            return Iterators.toFlux(messageManager.getMessages(MessageRange.one(uid), FetchGroup.FULL_CONTENT, session))
                .next();
        } catch (MailboxException e) {
            return Mono.error(e);
        }
    }

    private Pair<MessageId, MessageFastViewPrecomputedProperties> computeProjectionEntry(MessageResult messageResult) {
        try {
            return Pair.of(messageResult.getMessageId(), projectionItemFactory.from(messageResult));
        } catch (MailboxException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Mono<Void> storeProjectionEntry(Pair<MessageId, MessageFastViewPrecomputedProperties> pair) {
        return Mono.from(messageFastViewProjection.store(pair.getKey(), pair.getValue()));
    }
}
