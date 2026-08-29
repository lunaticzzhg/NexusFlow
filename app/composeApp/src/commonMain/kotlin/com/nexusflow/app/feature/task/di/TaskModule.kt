package com.nexusflow.app.feature.task.di

import com.nexusflow.app.feature.task.data.DefaultTaskRepository
import com.nexusflow.app.feature.task.data.TaskRemoteDataSource
import com.nexusflow.app.feature.task.data.createTaskApi
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailViewModel
import com.nexusflow.app.feature.task.presentation.home.TaskHomeViewModel
import de.jensklingenberg.ktorfit.Ktorfit
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val taskModule =
    module {
        single<TaskRepository> {
            DefaultTaskRepository(
                remoteDataSource =
                    TaskRemoteDataSource(
                        api = get<Ktorfit>().createTaskApi(),
                        apiCalls = get(),
                    ),
            )
        }
        viewModel { TaskHomeViewModel(get()) }
        viewModel { TaskCreateViewModel(get()) }
        viewModel { parameters -> TaskDetailViewModel(taskId = parameters.get(), repository = get()) }
    }
