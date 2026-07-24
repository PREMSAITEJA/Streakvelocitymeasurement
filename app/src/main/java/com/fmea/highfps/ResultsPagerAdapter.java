package com.fmea.highfps;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;
import java.util.List;
import java.util.Map;

public class ResultsPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_VECTORS = 0;
    private static final int TYPE_SENSOR_GRAPHS = 1;

    private List<VectorData> vectors;
    private Map<String, List<Entry>> sensorData;

    public ResultsPagerAdapter(List<VectorData> vectors, Map<String, List<Entry>> sensorData) {
        this.vectors = vectors;
        this.sensorData = sensorData;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_VECTORS : TYPE_SENSOR_GRAPHS;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_VECTORS) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.page_vector_field, parent, false);
            return new VectorViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.page_sensor_charts, parent, false);
            return new ChartsViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof VectorViewHolder) {
            ((VectorViewHolder) holder).vectorFieldView.setVectors(vectors);
        } else if (holder instanceof ChartsViewHolder) {
            ChartsViewHolder chartsHolder = (ChartsViewHolder) holder;
            setupLineChart(chartsHolder.chartCo2, sensorData.get("co2"), "CO₂ Level (ppm)", ColorTemplate.COLORFUL_COLORS[0]);
            setupLineChart(chartsHolder.chartTemp, sensorData.get("temp"), "Temperature (°C)", ColorTemplate.COLORFUL_COLORS[1]);
            setupLineChart(chartsHolder.chartHumidity, sensorData.get("humidity"), "Relative Humidity (%)", ColorTemplate.COLORFUL_COLORS[2]);
        }
    }

    private void setupLineChart(LineChart chart, List<Entry> entries, String label, int color) {
        if (entries == null || entries.isEmpty()) {
            chart.setNoDataText("No monitoring data recorded for this variable.");
            chart.invalidate();
            return;
        }
        LineDataSet dataSet = new LineDataSet(entries, label);
        dataSet.setColor(color);
        dataSet.setCircleColor(color);
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setGranularity(1f);
        chart.getDescription().setEnabled(false);
        chart.animateX(800);
        chart.invalidate();
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    static class VectorViewHolder extends RecyclerView.ViewHolder {
        VectorFieldView vectorFieldView;
        VectorViewHolder(View itemView) {
            super(itemView);
            vectorFieldView = itemView.findViewById(R.id.vectorFieldView);
        }
    }

    static class ChartsViewHolder extends RecyclerView.ViewHolder {
        LineChart chartCo2, chartTemp, chartHumidity;
        ChartsViewHolder(View itemView) {
            super(itemView);
            chartCo2 = itemView.findViewById(R.id.chartCo2);
            chartTemp = itemView.findViewById(R.id.chartTemp);
            chartHumidity = itemView.findViewById(R.id.chartHumidity);
        }
    }
}