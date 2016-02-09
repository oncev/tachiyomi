package eu.kanade.tachiyomi.ui.manga.chapter;

import android.os.Bundle;
import android.util.Pair;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import javax.inject.Inject;

import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.data.database.models.Chapter;
import eu.kanade.tachiyomi.data.database.models.Manga;
import eu.kanade.tachiyomi.data.download.DownloadManager;
import eu.kanade.tachiyomi.data.download.model.Download;
import eu.kanade.tachiyomi.data.preference.PreferencesHelper;
import eu.kanade.tachiyomi.data.source.SourceManager;
import eu.kanade.tachiyomi.data.source.base.Source;
import eu.kanade.tachiyomi.event.ChapterCountEvent;
import eu.kanade.tachiyomi.event.DownloadChaptersEvent;
import eu.kanade.tachiyomi.event.MangaEvent;
import eu.kanade.tachiyomi.event.ReaderEvent;
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter;
import icepick.State;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import timber.log.Timber;

public class ChaptersPresenter extends BasePresenter<ChaptersFragment> {

    @Inject DatabaseHelper db;
    @Inject SourceManager sourceManager;
    @Inject PreferencesHelper preferences;
    @Inject DownloadManager downloadManager;

    @State Manga manga;
    private Source source;
    private List<Chapter> chapters;
    @State boolean hasRequested;

    private PublishSubject<List<Chapter>> chaptersSubject;

    private static final int GET_MANGA = 1;
    private static final int DB_CHAPTERS = 2;
    private static final int FETCH_CHAPTERS = 3;
    private static final int CHAPTER_STATUS_CHANGES = 4;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        chaptersSubject = PublishSubject.create();

        startableLatestCache(GET_MANGA,
                () -> Observable.just(manga),
                ChaptersFragment::onNextManga);

        startableLatestCache(DB_CHAPTERS,
                this::getDbChaptersObs,
                ChaptersFragment::onNextChapters);

        startableFirst(FETCH_CHAPTERS,
                this::getOnlineChaptersObs,
                (view, result) -> view.onFetchChaptersDone(),
                (view, error) -> view.onFetchChaptersError());

        startableLatestCache(CHAPTER_STATUS_CHANGES,
                this::getChapterStatusObs,
                (view, download) -> view.onChapterStatusChange(download),
                (view, error) -> Timber.e(error.getCause(), error.getMessage()));

        registerForEvents();
    }

    @Override
    protected void onDestroy() {
        unregisterForEvents();
        EventBus.getDefault().removeStickyEvent(ChapterCountEvent.class);
        super.onDestroy();
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEvent(MangaEvent event) {
        this.manga = event.manga;
        start(GET_MANGA);

        if (isUnsubscribed(DB_CHAPTERS)) {
            source = sourceManager.get(manga.source);
            start(DB_CHAPTERS);

            add(db.getChapters(manga).asRxObservable()
                    .subscribeOn(Schedulers.io())
                    .doOnNext(chapters -> {
                        this.chapters = chapters;
                        EventBus.getDefault().postSticky(new ChapterCountEvent(chapters.size()));
                        for (Chapter chapter : chapters) {
                            setChapterStatus(chapter);
                        }
                        start(CHAPTER_STATUS_CHANGES);
                    })
                    .subscribe(chaptersSubject::onNext));
        }
    }

    public void fetchChaptersFromSource() {
        hasRequested = true;
        start(FETCH_CHAPTERS);
    }

    private void refreshChapters() {
        chaptersSubject.onNext(chapters);
    }

    private Observable<Pair<Integer, Integer>> getOnlineChaptersObs() {
        return source.pullChaptersFromNetwork(manga.url)
                .subscribeOn(Schedulers.io())
                .flatMap(chapters -> db.insertOrRemoveChapters(manga, chapters))
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<List<Chapter>> getDbChaptersObs() {
        return chaptersSubject.flatMap(this::applyChapterFilters)
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<List<Chapter>> applyChapterFilters(List<Chapter> chapters) {
        Observable<Chapter> observable = Observable.from(chapters)
                .subscribeOn(Schedulers.io());
        if (onlyUnread()) {
            observable = observable.filter(chapter -> !chapter.read);
        }
        if (onlyDownloaded()) {
            observable = observable.filter(chapter -> chapter.status == Download.DOWNLOADED);
        }
        return observable.toSortedList((chapter, chapter2) -> getSortOrder() ?
                Float.compare(chapter2.chapter_number, chapter.chapter_number) :
                Float.compare(chapter.chapter_number, chapter2.chapter_number));
    }

    private void setChapterStatus(Chapter chapter) {
        for (Download download : downloadManager.getQueue()) {
            if (chapter.id.equals(download.chapter.id)) {
                chapter.status = download.getStatus();
                return;
            }
        }

        if (downloadManager.isChapterDownloaded(source, manga, chapter)) {
            chapter.status = Download.DOWNLOADED;
        } else {
            chapter.status = Download.NOT_DOWNLOADED;
        }
    }

    private Observable<Download> getChapterStatusObs() {
        return downloadManager.getQueue().getStatusObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .filter(download -> download.manga.id.equals(manga.id))
                .doOnNext(this::updateChapterStatus);
    }

    public void updateChapterStatus(Download download) {
        for (Chapter chapter : chapters) {
            if (download.chapter.id.equals(chapter.id)) {
                chapter.status = download.getStatus();
                break;
            }
        }
        if (onlyDownloaded() && download.getStatus() == Download.DOWNLOADED)
            refreshChapters();
    }

    public Observable<Download> getDownloadProgressObs() {
        return downloadManager.getQueue().getProgressObservable()
                .filter(download -> download.manga.id.equals(manga.id))
                .observeOn(AndroidSchedulers.mainThread());
    }

    public void onOpenChapter(Chapter chapter) {
        EventBus.getDefault().postSticky(new ReaderEvent(source, manga, chapter));
    }

    public Chapter getNextUnreadChapter() {
        return db.getNextUnreadChapter(manga).executeAsBlocking();
    }

    public void markChaptersRead(Observable<Chapter> selectedChapters, boolean read) {
        add(selectedChapters
                .subscribeOn(Schedulers.io())
                .map(chapter -> {
                    chapter.read = read;
                    if (!read) chapter.last_page_read = 0;
                    return chapter;
                })
                .toList()
                .flatMap(chapters -> db.insertChapters(chapters).asRxObservable())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe());
    }

    public void markPreviousChaptersAsRead(Chapter selected) {
        Observable.from(chapters)
                .filter(c -> c.chapter_number > -1 && c.chapter_number < selected.chapter_number)
                .doOnNext(c -> c.read = true)
                .toList()
                .flatMap(chapters -> db.insertChapters(chapters).asRxObservable())
                .subscribe();
    }

    public void downloadChapters(Observable<Chapter> selectedChapters) {
        add(selectedChapters
                .toList()
                .subscribe(chapters -> {
                    EventBus.getDefault().postSticky(new DownloadChaptersEvent(manga, chapters));
                }));
    }

    public void deleteChapters(Observable<Chapter> selectedChapters) {
        add(selectedChapters
                .subscribe(chapter -> {
                    downloadManager.getQueue().remove(chapter);
                }, error -> {
                    Timber.e(error.getMessage());
                }, () -> {
                    if (onlyDownloaded())
                        refreshChapters();
                }));
    }

    public void deleteChapter(Chapter chapter) {
        downloadManager.deleteChapter(source, manga, chapter);
    }

    public void revertSortOrder() {
        manga.setChapterOrder(getSortOrder() ? Manga.SORT_ZA : Manga.SORT_AZ);
        db.insertManga(manga).executeAsBlocking();
        refreshChapters();
    }

    public void setReadFilter(boolean onlyUnread) {
        manga.setReadFilter(onlyUnread ? Manga.SHOW_UNREAD : Manga.SHOW_ALL);
        db.insertManga(manga).executeAsBlocking();
        refreshChapters();
    }

    public void setDownloadedFilter(boolean onlyDownloaded) {
        manga.setDownloadedFilter(onlyDownloaded ? Manga.SHOW_DOWNLOADED : Manga.SHOW_ALL);
        db.insertManga(manga).executeAsBlocking();
        refreshChapters();
    }

    public void setDisplayMode(int mode) {
        manga.setDisplayMode(mode);
        db.insertManga(manga).executeAsBlocking();
    }

    public boolean onlyDownloaded() {
        return manga.getDownloadedFilter() == Manga.SHOW_DOWNLOADED;
    }

    public boolean onlyUnread() {
        return manga.getReadFilter() == Manga.SHOW_UNREAD;
    }

    public boolean getSortOrder() {
        return manga.sortChaptersAZ();
    }

    public Manga getManga() {
        return manga;
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    public boolean hasRequested() {
        return hasRequested;
    }

}
