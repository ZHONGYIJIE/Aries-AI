/**
 * 跑马灯效果模块
 * 负责应用列表的滚动展示
 */

(function () {
  'use strict';

  const ARIES_DATA = window.ARIES_DATA || {
    apps: [
      '淘宝', '支付宝', '美团', '高德地图', '微信', 'QQ', '京东', '知乎', 'B站', '抖音', '小红书', '携程',
      '12306', '饿了么', '拼多多', '闲鱼', '快手', '网易云音乐', '微博', 'Keep', 'WPS', '大众点评', '滴滴出行', '百度地图',
      'QQ音乐', '腾讯视频', '爱奇艺', '优酷', '哔哩哔哩漫画', '淘宝特价版', '得物', '苏宁易购', '唯品会', '菜鸟',
      '豆瓣', '小宇宙', '喜马拉雅', '百度网盘', '夸克', 'UC浏览器', '百度', '今日头条', '腾讯新闻', '网易新闻',
      '微信读书', '飞书', '钉钉', '企业微信', '携程旅行', '去哪儿', '同程旅行', '滴滴',
    ],
  };

  function debounce(fn, delay) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), delay);
    };
  }

  function initMarqueeHome() {
    const row1 = document.getElementById('marquee-row-1');
    const row2 = document.getElementById('marquee-row-2');
    if (!row1 || !row2) return;

    const appsRow1 = ARIES_DATA.apps.filter((_, i) => i % 2 === 0);
    const appsRow2 = ARIES_DATA.apps.filter((_, i) => i % 2 === 1);

    // 简单应用域名映射用于获取 Favicon
    const appDomains = {
      '淘宝': 'taobao.com', '支付宝': 'alipay.com', '美团': 'meituan.com', '高德地图': 'amap.com',
      '微信': 'weixin.qq.com', 'QQ': 'im.qq.com', '京东': 'jd.com', '知乎': 'zhihu.com',
      'B站': 'bilibili.com', '抖音': 'douyin.com', '小红书': 'xiaohongshu.com', '携程': 'ctrip.com',
      '12306': '12306.cn', '饿了么': 'ele.me', '拼多多': 'pinduoduo.com', '闲鱼': '2.taobao.com',
      '快手': 'kuaishou.com', '网易云音乐': 'music.163.com', '微博': 'weibo.com', 'Keep': 'keep.com',
      'WPS': 'wps.cn', '大众点评': 'dianping.com', '滴滴出行': 'didiglobal.com', '百度地图': 'map.baidu.com',
      'QQ音乐': 'y.qq.com', '腾讯视频': 'v.qq.com', '爱奇艺': 'iqiyi.com', '优酷': 'youku.com',
      '得物': 'dewu.com', '苏宁易购': 'suning.com', '唯品会': 'vip.com', '豆瓣': 'douban.com',
      '百度网盘': 'pan.baidu.com', '夸克': 'quark.cn', '百度': 'baidu.com', '今日头条': 'toutiao.com',
      '腾讯新闻': 'news.qq.com', '网易新闻': 'news.163.com', '微信读书': 'weread.qq.com', '飞书': 'feishu.cn',
      '钉钉': 'dingtalk.com', '企业微信': 'work.weixin.qq.com'
    };

    function buildBase(trackEl, items) {
      trackEl.innerHTML = '';
      for (const name of items) {
        const el = document.createElement('div');
        el.className = 'app-capsule';

        // 尝试获取域名获取图标，如果没有默认使用 apple.com 作为兜底占位
        const domain = appDomains[name] || 'apple.com';
        const iconUrl = `https://icons.duckduckgo.com/ip3/${domain}.ico`;

        el.innerHTML = `
          <div class="app-icon-wrapper w-7 h-7 sm:w-8 sm:h-8 rounded-full bg-white/90 flex items-center justify-center shadow-sm overflow-hidden flex-shrink-0">
            <img src="${iconUrl}" alt="${name}" class="w-4 h-4 sm:w-5 sm:h-5 object-contain" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\\'http://www.w3.org/2000/svg\\' viewBox=\\'0 0 24 24\\'><path fill=\\'%2394a3b8\\' d=\\'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z\\'/></svg>'">
          </div>
          <span class="font-medium">${name}</span>
        `;
        trackEl.appendChild(el);
      }
    }

    function duplicateOnce(trackEl) {
      const copy = trackEl.innerHTML;
      trackEl.insertAdjacentHTML('beforeend', copy);
    }

    function initMarquee(wrapperId, items, direction) {
      const wrapper = document.getElementById(wrapperId);
      if (!wrapper) return;
      const track = wrapper.querySelector('.marquee-track');
      if (!track) return;

      function rebuild() {
        buildBase(track, items);
        let safety = 0;
        while (track.scrollWidth < window.innerWidth + 60 && safety < 12) {
          duplicateOnce(track);
          safety++;
        }
        duplicateOnce(track);

        track.classList.remove('marquee-left', 'marquee-right');
        track.classList.add(direction < 0 ? 'marquee-left' : 'marquee-right');
      }

      rebuild();
      window.addEventListener('resize', debounce(rebuild, 150));
    }

    initMarquee('marquee-row-1', appsRow1, -1);
    initMarquee('marquee-row-2', appsRow2, 1);
  }

  // 导出到全局
  window.initMarqueeHome = initMarqueeHome;

  // 自动初始化
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initMarqueeHome);
  } else {
    initMarqueeHome();
  }
})();





