package com.battlelancer.seriesguide.ui;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.adapters.MoviesDiscoverAdapter;
import com.battlelancer.seriesguide.enums.MoviesDiscoverLink;
import com.battlelancer.seriesguide.loaders.TmdbMoviesLoader;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.ui.dialogs.LanguageChoiceDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.MovieLocalizationDialogFragment;
import com.battlelancer.seriesguide.util.AutoGridLayoutManager;
import com.battlelancer.seriesguide.util.MovieTools;
import com.battlelancer.seriesguide.util.Utils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MoviesDiscoverFragment extends Fragment {

    @BindView(R.id.swipeRefreshLayoutMoviesDiscover) SwipeRefreshLayout swipeRefreshLayout;
    @BindView(R.id.recyclerViewMoviesDiscover) RecyclerView recyclerView;

    private MoviesDiscoverAdapter adapter;
    private GridLayoutManager layoutManager;
    private Unbinder unbinder;

    public MoviesDiscoverFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_movies_discover, container, false);
        unbinder = ButterKnife.bind(this, view);

        swipeRefreshLayout.setOnRefreshListener(onRefreshListener);
        swipeRefreshLayout.setRefreshing(false);

        adapter = new MoviesDiscoverAdapter(getContext(), itemClickListener);

        layoutManager = new AutoGridLayoutManager(getContext(),
                R.dimen.movie_grid_columnWidth, 2, 6);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int viewType = adapter.getItemViewType(position);
                if (viewType == MoviesDiscoverAdapter.VIEW_TYPE_LINK) {
                    return 3;
                }
                if (viewType == MoviesDiscoverAdapter.VIEW_TYPE_HEADER) {
                    return layoutManager.getSpanCount();
                }
                if (viewType == MoviesDiscoverAdapter.VIEW_TYPE_MOVIE) {
                    return 2;
                }
                return 0;
            }
        });

        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getLoaderManager().initLoader(0, null, nowPlayingLoaderCallbacks);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.movies_discover_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_action_movies_search_change_language) {
            MovieLocalizationDialogFragment dialog = MovieLocalizationDialogFragment.newInstance(0);
            dialog.show(getFragmentManager(), "dialog-language");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventLanguageChanged(LanguageChoiceDialogFragment.LanguageChangedEvent event) {
        String languageCode = getResources().getStringArray(
                R.array.languageCodesMovies)[event.selectedLanguageIndex];
        PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
                .putString(DisplaySettings.KEY_MOVIES_LANGUAGE, languageCode)
                .apply();

        getLoaderManager().restartLoader(0, null, nowPlayingLoaderCallbacks);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventTabClick(MoviesActivity.MoviesTabClickEvent event) {
        if (event.position == MoviesActivity.TAB_POSITION_DISCOVER) {
            recyclerView.smoothScrollToPosition(0);
        }
    }

    private MoviesDiscoverAdapter.ItemClickListener itemClickListener
            = new MoviesDiscoverAdapter.ItemClickListener() {
        @Override
        public void onClickLink(MoviesDiscoverLink link, View anchor) {
            Intent intent = new Intent(getContext(), MoviesSearchActivity.class);
            intent.putExtra(MoviesSearchActivity.EXTRA_ID_LINK, link.id);
            Utils.startActivityWithAnimation(getActivity(), intent, anchor);
        }

        @Override
        public void onClickMovie(int movieTmdbId, ImageView posterView) {
            // launch details activity
            Intent intent = new Intent(getActivity(), MovieDetailsActivity.class);
            intent.putExtra(MovieDetailsFragment.InitBundle.TMDB_ID, movieTmdbId);
            // transition poster
            Utils.startActivityWithTransition(getActivity(), intent, posterView,
                    R.string.transitionNameMoviePoster);
        }

        @Override
        public void onClickMovieMoreOptions(final int movieTmdbId, View anchor) {
            PopupMenu popupMenu = new PopupMenu(anchor.getContext(), anchor);
            popupMenu.inflate(R.menu.movies_popup_menu);

            // check if movie is already in watchlist or collection
            boolean isInWatchlist = false;
            boolean isInCollection = false;
            Cursor movie = getContext().getContentResolver().query(
                    SeriesGuideContract.Movies.buildMovieUri(movieTmdbId),
                    new String[] { SeriesGuideContract.Movies.IN_WATCHLIST,
                            SeriesGuideContract.Movies.IN_COLLECTION }, null, null, null
            );
            if (movie != null) {
                if (movie.moveToFirst()) {
                    isInWatchlist = movie.getInt(0) == 1;
                    isInCollection = movie.getInt(1) == 1;
                }
                movie.close();
            }

            Menu menu = popupMenu.getMenu();
            menu.findItem(R.id.menu_action_movies_watchlist_add).setVisible(!isInWatchlist);
            menu.findItem(R.id.menu_action_movies_watchlist_remove).setVisible(isInWatchlist);
            menu.findItem(R.id.menu_action_movies_collection_add).setVisible(!isInCollection);
            menu.findItem(R.id.menu_action_movies_collection_remove).setVisible(isInCollection);

            popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch (item.getItemId()) {
                        case R.id.menu_action_movies_watchlist_add: {
                            MovieTools.addToWatchlist(SgApp.from(getActivity()), movieTmdbId);
                            return true;
                        }
                        case R.id.menu_action_movies_watchlist_remove: {
                            MovieTools.removeFromWatchlist(SgApp.from(getActivity()), movieTmdbId);
                            return true;
                        }
                        case R.id.menu_action_movies_collection_add: {
                            MovieTools.addToCollection(SgApp.from(getActivity()), movieTmdbId);
                            return true;
                        }
                        case R.id.menu_action_movies_collection_remove: {
                            MovieTools.removeFromCollection(SgApp.from(getActivity()), movieTmdbId);
                            return true;
                        }
                    }
                    return false;
                }
            });
            popupMenu.show();
        }
    };

    private LoaderManager.LoaderCallbacks<TmdbMoviesLoader.Result> nowPlayingLoaderCallbacks
            = new LoaderManager.LoaderCallbacks<TmdbMoviesLoader.Result>() {
        @Override
        public Loader<TmdbMoviesLoader.Result> onCreateLoader(int id, Bundle args) {
            return new TmdbMoviesLoader(SgApp.from(getActivity()),
                    MoviesDiscoverAdapter.DISCOVER_LINK_DEFAULT, null);
        }

        @Override
        public void onLoadFinished(Loader<TmdbMoviesLoader.Result> loader,
                TmdbMoviesLoader.Result data) {
            if (!isAdded()) {
                return;
            }
            swipeRefreshLayout.setRefreshing(false);
            adapter.updateMovies(data.results);
        }

        @Override
        public void onLoaderReset(Loader<TmdbMoviesLoader.Result> loader) {
            adapter.updateMovies(null);
        }
    };

    private SwipeRefreshLayout.OnRefreshListener onRefreshListener
            = new SwipeRefreshLayout.OnRefreshListener() {
        @Override
        public void onRefresh() {
            getLoaderManager().restartLoader(0, null, nowPlayingLoaderCallbacks);
        }
    };
}
