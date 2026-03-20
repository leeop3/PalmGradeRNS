package com.palmgrade.rns.ui.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.palmgrade.rns.R;
import com.palmgrade.rns.grading.BunchRecord;
import com.palmgrade.rns.storage.BunchRecordRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * HistoryFragment
 *
 * Tab 2 — Submission history for today.
 *
 * Shows three summary stats (total / sent / queued) and a scrollable
 * RecyclerView of BunchRecord cards. LiveData observer auto-refreshes
 * the list whenever the database changes.
 */
public class HistoryFragment extends Fragment {

    private TextView tvTotal, tvSent, tvQueued;
    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private BunchRecordRepository repository;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvTotal      = view.findViewById(R.id.tv_stat_total);
        tvSent       = view.findViewById(R.id.tv_stat_sent);
        tvQueued     = view.findViewById(R.id.tv_stat_queued);
        recyclerView = view.findViewById(R.id.rv_history);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new HistoryAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        repository = BunchRecordRepository.get(requireContext());

        // LiveData auto-updates whenever the DB changes
        repository.getTodayLive().observe(getViewLifecycleOwner(), this::onRecordsChanged);
    }

    private void onRecordsChanged(List<BunchRecord> records) {
        adapter.update(records);

        long sent   = records.stream().filter(r -> r.transmitted).count();
        long queued = records.size() - sent;

        tvTotal.setText(String.valueOf(records.size()));
        tvSent.setText(String.valueOf(sent));
        tvQueued.setText(String.valueOf(queued));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView adapter
    // ─────────────────────────────────────────────────────────────────────────

    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

        private List<BunchRecord> items;

        HistoryAdapter(List<BunchRecord> items) {
            this.items = items;
        }

        void update(List<BunchRecord> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_record, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BunchRecord r = items.get(pos);

            // Short UUID prefix + block ID
            h.tvId.setText(r.uuid.substring(0, 8).toUpperCase() + " · " + r.blockId);
            h.tvTime.setText(r.getFormattedTime());
            h.tvGrades.setText(r.getSummary());
            h.tvCoords.setText(r.getFormattedCoords());

            // Transmission status pill
            boolean sent = r.transmitted;
            h.tvStatus.setText(sent ? "SENT" : (r.txAttempts >= 5 ? "FAILED" : "QUEUED"));
            h.tvStatus.setBackgroundResource(
                sent ? R.drawable.bg_pill_green
                     : (r.txAttempts >= 5 ? R.drawable.bg_pill_red : R.drawable.bg_pill_amber));
            h.tvStatus.setTextColor(h.tvStatus.getContext().getResources().getColor(
                sent ? R.color.text_success
                     : (r.txAttempts >= 5 ? R.color.text_danger : R.color.text_warning),
                null));
        }

        @Override
        public int getItemCount() {
            return items == null ? 0 : items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvId, tvTime, tvGrades, tvCoords, tvStatus;

            VH(@NonNull View v) {
                super(v);
                tvId     = v.findViewById(R.id.tv_record_id);
                tvTime   = v.findViewById(R.id.tv_record_time);
                tvGrades = v.findViewById(R.id.tv_record_grades);
                tvCoords = v.findViewById(R.id.tv_record_coords);
                tvStatus = v.findViewById(R.id.tv_record_status);
            }
        }
    }
}
