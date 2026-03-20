package com.palmgrade.rns.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.palmgrade.rns.ui.connection.ConnectionFragment;
import com.palmgrade.rns.ui.export.ExportFragment;
import com.palmgrade.rns.ui.grading.GradingFragment;
import com.palmgrade.rns.ui.history.HistoryFragment;

/**
 * MainPagerAdapter
 *
 * Provides the four tab fragments to the ViewPager2 in MainActivity.
 *
 *   Position 0 → ConnectionFragment  (Radio & BT settings)
 *   Position 1 → GradingFragment     (Bunch quality checker)
 *   Position 2 → HistoryFragment     (Today's submission history)
 *   Position 3 → ExportFragment      (End-of-day ZIP export)
 *
 * offscreenPageLimit(3) is set in MainActivity so all fragments stay
 * alive — this ensures GPS keeps running while the user is on other tabs
 * and the history list stays responsive.
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int TAB_CONNECTION = 0;
    public static final int TAB_GRADING    = 1;
    public static final int TAB_HISTORY    = 2;
    public static final int TAB_EXPORT     = 3;
    public static final int TAB_COUNT      = 4;

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case TAB_CONNECTION: return new ConnectionFragment();
            case TAB_GRADING:   return new GradingFragment();
            case TAB_HISTORY:   return new HistoryFragment();
            case TAB_EXPORT:    return new ExportFragment();
            default:            return new ConnectionFragment();
        }
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }
}
