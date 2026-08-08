package com.nexusflow.app.feature.task.di

import com.nexusflow.app.feature.task.data.MockTaskRepository
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.home.TaskHomeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val taskModule =
    module {
        single<TaskRepository> { MockTaskRepository() }
        viewModel { TaskHomeViewModel(get()) }
        viewModel { TaskCreateViewModel(get()) }
    }
