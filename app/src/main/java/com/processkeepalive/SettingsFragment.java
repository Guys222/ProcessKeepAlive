package com.processkeepalive;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsFragment extends Fragment {

    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        SwitchMaterial switchMaster = view.findViewById(R.id.switch_master);
        SwitchMaterial switchAutoStart = view.findViewById(R.id.switch_auto_start);

        switchMaster.setChecked(prefs.getBoolean(Prefs.KEY_ENABLED, true));
        switchMaster.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply());

        switchAutoStart.setChecked(prefs.getBoolean(Prefs.KEY_AUTO_START, false));
        switchAutoStart.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_AUTO_START, checked).apply());

        view.findViewById(R.id.btn_copy_author).setOnClickListener(v ->
                copy("Guys222"));
        view.findViewById(R.id.btn_copy_repo).setOnClickListener(v ->
                copy("www.baidu.com"));

        TextView version = view.findViewById(R.id.version_value);
        version.setText(BuildConfig.VERSION_NAME);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
        }
        Toast.makeText(requireContext(), "已复制：" + text, Toast.LENGTH_SHORT).show();
    }
}
