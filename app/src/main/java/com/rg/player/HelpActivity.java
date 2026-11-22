package com.rg.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 说明页：先显示目录，点进去看内容，不再用弹窗。 */
public class HelpActivity extends AppCompatActivity {

    private static final class Topic {
        final String title;
        final String summary;
        final String body;

        Topic(String title, String summary, String body) {
            this.title = title;
            this.summary = summary;
            this.body = body;
        }
    }

    private final List<Topic> topics = new ArrayList<>();
    private RecyclerView list;
    private View detail;
    private TextView detailText;
    private TextView title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);
        FontApplier.apply(this, findViewById(android.R.id.content));

        list = findViewById(R.id.list);
        detail = findViewById(R.id.detail);
        detailText = findViewById(R.id.detail_text);
        title = findViewById(R.id.title);
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());

        buildTopics();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new Adapter());
    }

    private void buildTopics() {
        topics.add(new Topic("封面 / 缩略图", "同名图片放在视频旁边", ""
                + "封面文件放在视频同一目录，文件名与视频同名，扩展名可以是 .jpg / .jpeg / .png / .webp。\n\n"
                + "例：Movie.mkv → Movie.jpg\n\n"
                + "也支持放在同目录的 poster.jpg（该目录所有视频共用）。\n\n"
                + "找不到封面时，会临时读取视频第一帧作为缩略图，只在内存里使用，不会写入任何缓存文件。\n\n"
                + "如果封面换过但显示没变，回到设置点「清除缓存」即可重新读取。"));

        topics.add(new Topic("手柄与手势", "按键映射与触屏操作", ""
                + "手柄：\n"
                + "A / 回车 / 空格：播放 / 暂停\n"
                + "B：退出播放器\n"
                + "十字左 / 右：快退 / 快进 10 秒\n"
                + "十字上 / 下：上一部 / 下一部\n"
                + "L1 / R1：上一个 / 下一个视频\n"
                + "返回键：打开 / 关闭功能菜单\n\n"
                + "触屏：\n"
                + "单击：显示 / 隐藏控制层\n"
                + "双击：暂停 / 继续播放\n"
                + "长按播放键：功能菜单\n"
                + "长按画面：左右滑动调速 0.5X–4.0X，松手保留\n"
                + "左右滑动：拖动进度，边缘 28dp 内为系统返回\n"
                + "上下滑：左 1/4 屏调亮度，右 1/4 屏调应用内音量\n"
                + "十字上 / 下：上一部 / 下一部"));
}

    private void showDetail(Topic topic) {
        title.setText(topic.title);
        detailText.setText(topic.body);
        list.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        detail.scrollTo(0, 0);
    }

    private void showList() {
        title.setText("说明");
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (detail.getVisibility() == View.VISIBLE) {
            showList();
        } else {
            super.onBackPressed();
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_help, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Topic t = topics.get(position);
            h.title.setText(t.title);
            h.summary.setText(t.summary);
            h.itemView.setOnClickListener(v -> showDetail(t));
        }

        @Override
        public int getItemCount() {
            return topics.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView summary;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.help_title);
                summary = itemView.findViewById(R.id.help_summary);
            }
        }
    }
}
