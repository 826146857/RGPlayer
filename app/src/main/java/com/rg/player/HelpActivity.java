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
        topics.add(new Topic("按键", "按页面区分", ""
                + "首页：\n"
                + "方向键选中；确认键 / 点击打开；返回键退出；下拉刷新。\n\n"
                + "视频播放器（Media3）：\n"
                + "A 播放/暂停，B 退出，L1/R1 上一个/下一个视频，X 顺序切滤镜，Y 选滤镜，返回键菜单。\n"
                + "支持手势缩放、旋转、平移。\n\n"
                + "视频播放器（VLC）：\n"
                + "A 播放/暂停，B 退出，L1/R1 切换视频；不支持旋转和滤镜，按 X/Y 会提示不支持。\n\n"
                + "GBA 影片：\n"
                + "A 暂停，L/R 快退快进，START 菜单，X 切遮罩，Y 选遮罩，L2/R2 换片，BACK 退出。\n"
                + "点屏幕任意位置显示/隐藏左上角提示。\n\n"
                + "设置 / 说明：\n"
                + "方向键移动，确认键进入 / 切换，返回键回上一级。"));

        topics.add(new Topic("封面规则", "视频和 GBA 共用", ""
                + "优先级从高到低：\n"
                + "1. 与文件同名：Movie.mkv → Movie.jpg，扩展名 .jpg / .jpeg / .png / .webp\n"
                + "2. 同名加后缀：Movie-poster.jpg、Movie.poster.png、Movie-cover.webp\n"
                + "3. 目录级封面：poster / folder / cover + 图片扩展名（该目录共用）\n\n"
                + "GBA 影片同样适用，并会额外尝试去掉「GBA电影- / GBA视频-」前缀后的文件名。\n\n"
                + "没有自定义封面时：视频抓首帧，GBA 用核心抓帧，都会缓存。\n"
                + "换了封面但显示没变，到设置点「清除缓存」再重新进入。"));
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
