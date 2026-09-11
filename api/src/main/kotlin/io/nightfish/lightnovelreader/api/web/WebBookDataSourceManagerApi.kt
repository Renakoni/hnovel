package io.nightfish.lightnovelreader.api.web

import io.nightfish.lightnovelreader.api.identifier.Identifier

/**
 * 网络书本数据源管理接口
 * 提供注册和注销能力。宿主按照请求的来源身份解析运行时，没有全局激活的数据源。
 *
 * @since Api 2
 */
interface WebBookDataSourceManagerApi {
    /**
     * 注册一个网络数据源
     *
     * @param webBookDataSource 要注册的数据源实现
     * @param webDataSourceItem 该数据源的元数据描述
     *
     * @since Api 2
     */
    fun registerWebDataSource(
        webBookDataSource: WebBookDataSource,
        webDataSourceItem: WebDataSourceItem
    )

    /**
     * 注销指定id的网络数据源
     *
     * @param webDataSourceId 要注销的数据源id
     *
     * @since Api 4
     */
    fun unregisterWebDataSource(webDataSourceId: Identifier)

}
