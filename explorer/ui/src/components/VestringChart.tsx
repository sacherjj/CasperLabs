import React, { Component } from 'react';
import { Line } from 'react-chartjs-2';
import moment from 'moment';
import { VestingDetail } from '../containers/VestingContainer';

interface Props {
  vestingDetail: VestingDetail;
}

class VestingChart extends Component<Props, {}> {
  render() {
    console.log(this.props.vestingDetail);
    return <Line data={this.chartData()} options={this.chartOptions()}/>;
  }

  chartData() {
    return {
      datasets: [
        this.fromBaseDataset({
          data: this.getPoints()
        })
      ]
    };
  }

  getPoints() {
    const {
      total_amount,
      on_pause_duration,
      cliff_timestamp,
      drip_duration
    } = this.props.vestingDetail;
    const points = [];
    let time = cliff_timestamp + on_pause_duration;
    let p;
    do {
      p = this.getDataPointAt(time);
      points.push(p);
      time += drip_duration;
    } while (p.y < total_amount);
    points.push(this.getDataPointAt(Date.now()/ 1000));
    let ret = points
      .sort((a, b) => a.x - b.x)
      .map(x => {
        return {
          x: this.formatDate(x.x * 1000),
          y: x.y
        };
      });

    return ret;
  }

  getDataPointAt(date: number) {
    return {
      x: date,
      y: this.getAmountAt(date)
    };
  }

  formatDate(date: number) {
    return moment(date).format('MM/DD/YYYY HH:mm');
  }

  getAmountAt(date: number) {
    const {
      total_amount,
      on_pause_duration,
      cliff_amount,
      cliff_timestamp,
      drip_amount,
      drip_duration
    } = this.props.vestingDetail;
    if (date < cliff_timestamp + on_pause_duration) {
      return 0;
    }
    const computed =
      cliff_amount + ((date - cliff_timestamp) / drip_duration) * drip_amount;

    return Math.min(total_amount, computed);
  }

  chartOptions() {
    return {
      legend: { display: false },
      title: {
        display: true,
        text: 'Vesting Schedule',
        position: 'top',
        fontSize: 16
      },
      scales: {
        xAxes: [
          {
            type: 'time',
            time: {
              parser: 'MM/DD/YYYY HH:mm',
              tooltipFormat: 'll HH:mm'
            },
            scaleLabel: {
              display: true,
              labelString: 'Date'
            }
          }
        ],
        yAxes: [
          {
            scaleLabel: {
              display: true,
              labelString: 'CLX'
            },
            ticks: {
              beginAtZero: true
            }
          }
        ]

      }
    };
  }

  fromBaseDataset(opts: any) {
    return {
      lineTension: 0.1,
      backgroundColor: 'rgba(92,182,228,0.4)',
      borderColor: 'rgba(92,182,228,1)',
      borderJoinStyle: 'miter',
      pointBorderColor: 'rgba(92,182,228,1)',
      pointBackgroundColor: 'rgba(92,182,228,1)',
      pointBorderWidth: 1,
      pointHoverRadius: 5,
      pointHoverBackgroundColor: 'rgba(92,182,228,1)',
      pointHoverBorderColor: 'rgba(220,220,220,1)',
      pointHoverBorderWidth: 2,
      pointRadius: 5,
      pointHitRadius: 10,
      ...opts
    };
  }
}

export default VestingChart;
