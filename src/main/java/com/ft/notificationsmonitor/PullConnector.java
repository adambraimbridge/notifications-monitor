package com.ft.notificationsmonitor;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.http.javadsl.model.Query;
import akka.http.javadsl.model.Uri;
import akka.japi.Creator;
import akka.japi.Pair;
import com.ft.notificationsmonitor.http.PullHttp;
import com.ft.notificationsmonitor.model.DatedEntry;
import com.ft.notificationsmonitor.model.Link;
import com.ft.notificationsmonitor.model.PullEntry;
import com.ft.notificationsmonitor.model.PullPage;
import scala.collection.JavaConverters;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class PullConnector extends UntypedActor {

    static final String REQUEST_SINCE_LAST = "RequestSinceLast";
    static final String CONTINUE_REQUESTING_SINCE_LAST = "ContinueRequestingSinceLast";

    private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private PullHttp pullHttp;
    private List<ActorRef> pairMatchers;
    private Query lastQuery = Query.create(new Pair<>("since", ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)));

    public PullConnector(PullHttp pullHttp, List<ActorRef> pairMatchers) {
        this.pullHttp = pullHttp;
        this.pairMatchers = pairMatchers;
    }

    @Override
    public void onReceive(Object message) {
        if (message.equals(REQUEST_SINCE_LAST)) {
            makeRequestsUntilEmpty(true);
        } else if (message.equals(CONTINUE_REQUESTING_SINCE_LAST)) {
            makeRequestsUntilEmpty(false);
        }
    }

    private void makeRequestsUntilEmpty(final boolean firstInSeries) {
        CompletionStage<PullPage> pageF = pullHttp.makeRequest(lastQuery);
        pageF.whenComplete((page, failure) -> {
            if (failure != null) {
                log.error(failure, "Failed notifications pull request.");
            } else {
                parseNotificationEntries(page, firstInSeries);
                parseLinkAndScheduleNextRequest(page);
            }
        });
    }

    private void parseNotificationEntries(final PullPage page, final boolean firstInSeries) {
        final Collection<PullEntry> notifications = JavaConverters.asJavaCollection(page.notifications());
        if (notifications.isEmpty()) {
            if (firstInSeries) {
                log.info("heartbeat");
            }
        } else {
            notifications.forEach(entry -> {
                log.info(entry.id());
                final DatedEntry datedEntry = new DatedEntry(entry, ZonedDateTime.now());
                pairMatchers.forEach(pairMatcher -> pairMatcher.tell(datedEntry, self()));
            });
        }
    }

    private void parseLinkAndScheduleNextRequest(final PullPage page) {
        final Collection<Link> links = JavaConverters.asJavaCollection(page.links());
        Optional<Query> currentQuery = links.stream().findFirst().map(link -> {
            String href = link.href();
            Uri uri = Uri.create(href);
            return uri.query();
        });
        currentQuery.ifPresent(query -> {
            if (!query.equals(lastQuery)) {
                this.lastQuery = query;
                getSelf().tell(CONTINUE_REQUESTING_SINCE_LAST, getSelf());
            }
        });
    }

    public static Props props(final PullHttp pullHttp, final List<ActorRef> pairMatchers) {
        return Props.create(new Creator<PullConnector>() {
            private static final long serialVersionUID = 1L;

            @Override
            public PullConnector create() throws Exception {
                return new PullConnector(pullHttp, pairMatchers);
            }
        });
    }
}
