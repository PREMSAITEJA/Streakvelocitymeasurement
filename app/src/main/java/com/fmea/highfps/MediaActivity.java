package com.fmea.highfps;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaActivity extends AppCompatActivity {

    private RecyclerView rvSessionFolders;
    private SessionAdapter adapter;
    private final List<File> sessionFoldersList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media);

        rvSessionFolders = findViewById(R.id.rvSessionFolders);
        rvSessionFolders.setLayoutManager(new LinearLayoutManager(this));

        loadCompletedSessions();
    }

    private void loadCompletedSessions() {
        StorageManager storageManager = new StorageManager(this);
        if (storageManager.initFramesDirectory()) {
            File baseFramesDir = storageManager.getFramesDirectory();
            File[] folders = baseFramesDir.listFiles(File::isDirectory);

            if (folders != null && folders.length > 0) {
                Arrays.sort(folders, (a, b) -> b.getName().compareTo(a.getName()));
                sessionFoldersList.addAll(Arrays.asList(folders));
            }
        }

        adapter = new SessionAdapter(this, sessionFoldersList, session -> {
            Intent intent = new Intent(MediaActivity.this, AnalysisActivity.class);
            intent.putExtra("SESSION_PATH", session.getAbsolutePath());
            startActivity(intent);
        });
        rvSessionFolders.setAdapter(adapter);
    }

    private static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {
        private final Context context;
        private final List<File> folders;
        private final OnSessionClickListener listener;

        public interface OnSessionClickListener {
            void onSessionClick(File sessionFolder);
        }

        public SessionAdapter(Context context, List<File> folders, OnSessionClickListener listener) {
            this.context = context;
            this.folders = folders;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_session_folder, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File folder = folders.get(position);
            holder.tvName.setText(folder.getName());

            File framesSubdir = new File(folder, SessionPathManager.FRAMES_SUBFOLDER);
            File[] tiffFiles = framesSubdir.listFiles((dir, name) -> name.endsWith(".tiff") || name.endsWith(".tif"));
            int count = (tiffFiles != null) ? tiffFiles.length : 0;
            holder.tvMeta.setText(count + " high-speed frames recorded");

            holder.itemView.setOnClickListener(v -> listener.onSessionClick(folder));
        }

        @Override
        public int getItemCount() {
            return folders.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvMeta;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvSessionName);
                tvMeta = itemView.findViewById(R.id.tvSessionMeta);
            }
        }
    }
}