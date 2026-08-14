/**
 * UI 框架
 */
class UI {
	_layer;
	static DropdownDefaultHidden = false;
	/**
	 * 初始化 UI
	 * @returns 返回一个 Promise 对象
	 */
	static Init() {
		const promise = new Promise((resolve, reject) => {
			layui.use(['dropdown', 'layer', 'form'], () => {
				this._layer = layer
				UI.AttachEventHandlerToUI();
				UI.RenderList('.map-selector', mapList);
				// UI.RenderList('.theme-selector', [
				// 	{
				// 		"id": '2001',
				// 		"title": "默认"
				// 	}
				// ]);
				//UI.DisableRightClickEvent();
				resolve();
			});
		});
		/* https://developer.mozilla.org/zh-CN/docs/Web/API/MutationObserver DOM 节点变化监听*/
		let callback = function FengMapDOMChangeHandler(mutationsList, observer) {
			if (mutationsList[0].target.id == "") {
				UI.AttachEventHandlerToDomMarker()
			}
		}
		const observer = new MutationObserver(callback);
		/* 当节点发生变化时，动态绑定事件 */
		observer.observe(document.getElementById('fengmap'), { childList: true, subtree: true });
		return promise;
	}
	static DisableRightClickEvent() {
		$('#aim-box').click(() => { return false });
		$('#aim-box').contextmenu(() => { return false });
	}
	static AttachEventHandlerToUI() {
		var btnAction = {
			"SetOverviewMode": (obj, val) => {
				SetOverviewMode(val);
			},
		}
		$('div.overview-mode-selector .layui-btn').on('click', function () {
			var othis = $(this), method = othis.data('method'), val = othis.data('value');
			btnAction[method] ? btnAction[method].call(this, othis, val) : '';
			$('.overview-mode-selector').children().removeClass('fm-layui-btn-active');
			othis.addClass('fm-layui-btn-active');
		});
		$('.dropdown-toggle').on('click', function () {
			const dropdownArea = $('.fm-dropdown-area');
			const willHide = !dropdownArea.hasClass('is-hidden');
			dropdownArea.toggleClass('is-hidden', willHide);
			$(this).text(willHide ? '\u663e\u793a\u5217\u8868' : '\u9690\u85cf\u5217\u8868');
		});
	}
	/**
	 * 向动态创建的 DOM 元素绑定事件
	 */
	static AttachEventHandlerToDomMarker() {
		var btnAction = {
			"SetStart": () => {
				$('#start_pos').val(_MapMarker.x + ',' + _MapMarker.y);
				_StartMarker == null ? _StartMarker = AddImageMarker({ x: _MapMarker.x, y: _MapMarker.y, buildingID: _MapMarker.buildingID }) : MoveImageMarker(_StartMarker, { x: _MapMarker.x, y: _MapMarker.y, buildingID: _MapMarker.buildingID });
				_Navigator.setStartPoint(_StartMarker, true);
				RemoveMarker(_MapMarker);
				if (_StartMarker && _DestMarker) {
					Route();
				}
			},
			"SetDest": () => {
				$('#dest_pos').val(_MapMarker.x + ',' + _MapMarker.y);
				_DestMarker == null ? _DestMarker = AddImageMarker({ x: _MapMarker.x, y: _MapMarker.y, buildingID: _MapMarker.buildingID }) : MoveImageMarker(_DestMarker, { x: _MapMarker.x, y: _MapMarker.y, buildingID: _MapMarker.buildingID });
				_Navigator.setDestPoint(_DestMarker, true);
				RemoveMarker(_MapMarker);
				if (_StartMarker && _DestMarker) {
					Route();
				}
			},
		}
		$('button.layui-btn').on('click', function () {
			var othis = $(this), method = othis.data('method');
			btnAction[method] ? btnAction[method].call(this, othis) : '';
		});
	}
	/**
	 * 动态渲染下拉框，并绑定事件
	 * @param {*} elem 目标下拉框对象
	 * @param {*} data 绑定的数据
	 */
	static RenderList(elem, data) {
		// 格式化每条数据文本为 "title：id"
		const formatData = data.map(item => {
			return {
				id: item.id,
				title: `${item.title}：${item.id}`,
				originTitle: `${item.title}：${item.id}`, // 保存原始标题用于修改按钮文字
			}
		})
		const trigger = $(elem);
		const handleClick = function (obj) {
				switch (elem) {
					case '.theme-selector':
						InitAnotherTheme(obj.id);
						break;
					case '.map-selector':
						InitAnotherMap(obj.id);
						break;
					case '.focus-selector':
						SetFocusMode(obj.id);
						break;
					case '.mode-selector':
						SetOverviewMode(obj.id);
						break;
					case '.tileLayer-selector':
						UI.SetTileLayerSelector(obj.id);
						break;
					case '.tile3d-selector':
						Tile3dHandleClick(obj);
						break;
					case '.cluster-selector':
						MarkerClusterHandleClick(obj);
						break;
					default:
						break;
				}
		};

		const panelClass = elem === '.map-selector' ? 'fm-map-dropdown' : 'fm-theme-dropdown';
		const panel = $(`<div class="layui-dropdown layui-border-box layui-panel fm-persistent-dropdown ${panelClass}"></div>`);
		const menu = $('<ul class="layui-menu layui-dropdown-menu"></ul>');
		const selectedMapID = window.currentMapID || data[0]?.id;
		formatData.forEach((obj, index) => {
			const item = $('<li><div class="layui-menu-body-title"></div></li>');
			item.data('map-id', obj.id);
			item.toggleClass('is-selected', obj.id === selectedMapID);
			item.find('.layui-menu-body-title').text(obj.title);
			item.on('click', function () {
				menu.children().removeClass('is-selected');
				item.addClass('is-selected');
				handleClick(obj);
			});
			menu.append(item);
		});
		panel.append(menu);
		let dropdownArea = $('.fm-dropdown-area');
		if (!dropdownArea.length) {
			dropdownArea = $('<div class="fm-dropdown-area"></div>');
			$('.layui-container').append(dropdownArea);
			dropdownArea.toggleClass('is-hidden', UI.DropdownDefaultHidden);
			$('.dropdown-toggle').text(UI.DropdownDefaultHidden ? '\u663e\u793a\u5217\u8868' : '\u9690\u85cf\u5217\u8868');
		}
		dropdownArea.append(panel);
	}
	static SetTileLayerSelector(type) {
		if (type === 'tilelayer') {
			// 矢量瓦片
			SetTileLayerMode(type)
		} else if (type === 'tilelayerAMAP') {
			// 卫星影像
			SetTileLayerMode(type)
		} else if (type === 'show') {
			// 显示/隐藏
			toggleTileLayerVisibility()
		} else if (type === 'removeTileLayer') {
			// 删除
			removeTileLayer()
		}
	}
	/**
	 * 启动 Loading
	 */
	static Loading() {
		layer.msg('加载中', {
			icon: 16
			, shade: 0.8
			, time: false
		});
	}
	/**
	 * 关闭 Loading
	 */
	static Completed() {
		this._layer.closeAll();
	}
	/* 一个弹框 */
	static Toast(msg) {
		layer.alert(msg);
	}
	static RenderInfo(msg) {
		$('.info-body').text(msg)
	}
}
