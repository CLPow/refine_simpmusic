package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NotificationViewModel(
    commonRepository: CommonRepository,
) : BaseViewModel() {
    private var _listNotification: MutableStateFlow<List<NotificationEntity>?> =
        MutableStateFlow(null)
    val listNotification: StateFlow<List<NotificationEntity>?> = _listNotification

    init {
        viewModelScope.launch {
            commonRepository.getAllNotifications().collect { notificationEntities ->
                _listNotification.value =
                    notificationEntities
                        // Nothing produces blog entries any more, but rows written by an earlier
                        // build are still in the database and would otherwise keep showing the
                        // upstream developer's posts in this list forever.
                        ?.filterNot { it.type == NotificationEntity.TYPE_BLOG }
                        ?.sortedByDescending { it.time }
            }
        }
    }
}