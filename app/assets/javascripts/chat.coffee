$(document).ready () ->
  $('[data-toggle="offcanvas"]').click () ->
    $('.row-offcanvas').toggleClass('active')

body = -> $("body")
msgform = -> $("#msgform")
createTopicForm = -> $("#topicform")
comment = -> $("#comment")
topicNameEl = -> $("#topicName")
currentTopicEl = -> $("#current-topic")
confirmButton = -> $("#sendMessageButton")
createTopicButton = -> $("#createTopicButton")
messages = -> $("#messages")
messagesInfo = -> $("#messages .messages-info")
topics = -> $("#topics")
topicFormTemplate = -> $('#topic-form-template')
messagesInfoTemplate = -> $('#messages-info-template')
olderButtonTemplate = -> $('#older-btn-template')
olderButtonContainer = -> $('#older-btn-container')
olderButton = -> $('#older-btn')
topicsPanel = -> $("#topics-panel .topics-panel")
messageOnLeftTemplate = -> $("#message-on-left-template")
topicsOnLeftTemplate = -> $("#topics-on-left-template")

messageExists = ->
  comment().val().trim().length

topicNameEntered = ->
  topicNameEl().val().trim().length

changeConfirmButtonState = (disable) ->
  confirmButton().prop "disabled", disable

enableConfirmButton = ->
  changeConfirmButtonState(false)

disableConfirmButton = ->
  changeConfirmButtonState(true)

resetForm = ->
  comment().val("")

resetTopicForm = ->
  topicNameEl().val("")

init = ->
  disableConfirmButton()

$ ->
  init()

  templateScript =
    messageOnLeft: messageOnLeftTemplate().html(),
    topicsOnLeft: topicsOnLeftTemplate().html(),
    olderButton: olderButtonTemplate().html(),
    topicForm: topicFormTemplate().html(),
    messagesInfo: messagesInfoTemplate().html(),

  template =
    messageOnLeft: Handlebars.compile(templateScript.messageOnLeft),
    topicsOnLeft: Handlebars.compile(templateScript.topicsOnLeft),
    olderButton: Handlebars.compile(templateScript.olderButton),
    topicForm: Handlebars.compile(templateScript.topicForm),
    messagesInfo: Handlebars.compile(templateScript.messagesInfo)

  chatState = new ChatState

  ws = new WebSocket $("body").data("ws-url")
  ws.onmessage = (event) ->
    message = JSON.parse event.data
    switch message.type
      when "messages"
        if message.query.topic != chatState.currentTopic
          return
        if chatState.oldestMessageDate? and message.query.date != chatState.oldestMessageDate
          return
        if chatState.isLoadingInitialMessages or chatState.noMessages?
          messages().html("")
        oldFirstMessageElem = messages().find(".chat-msg").first()
        chatState.handleNewMessages message, (msgs) ->
          hadOldFirstElem = oldFirstMessageElem.length > 0
          if not hadOldFirstElem
            msgs.slice().reverse().forEach (msg) ->
              messages().prepend(messageOnLeft(msg.user, msg.text))
            if not message.isLast
              messages().prepend(template.olderButton())
            body().scrollTop(body().prop("scrollHeight"))
            return
          oldScrollTop = $(window).scrollTop()
          olderButtonCont = olderButtonContainer()
          scrollDelta = 0
          if olderButtonCont.length
            scrollDelta -= olderButtonContainer().outerHeight()
            olderButtonCont.remove()
          if chatState.newMessageArrivedWhileLoading?
            messages().html("")
          msgs.slice().reverse().forEach (msg) ->
            elem = $(messageOnLeft(msg.user, msg.text))
            messages().prepend(elem)
            scrollDelta += elem.outerHeight()
          if not message.isLast
            elem = $(template.olderButton())
            messages().prepend(elem)
            scrollDelta += elem.outerHeight()
          $(window).scrollTop(oldScrollTop + scrollDelta)
      when "message"
        if chatState.noMessages?
          messages().html("")
        chatState.handleNewMessage message
        messages().append(messageOnLeft(message.msg.user, message.msg.text))
        messages().scrollTop(messages().prop("scrollHeight"))
      when "topicName"
        chatState.topicNames[message.topicId] = message.topicName
        if chatState.noTopics
          topics().html("")
          topics().append(template.topicForm())
        chatState.handleNewTopic message
        topics().append(topicsOnLeft(message.topicName, message.topicId))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "topics"
        topics().html("")
        topics().append(template.topicForm())
        chatState.handleTopicsList(message.topics)
        message.topics.forEach (topic) ->
          chatState.topicNames[topic.id] = topic.name
          topics().append(topicsOnLeft(topic.name, topic.id))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "no topics found"
        chatState.handleNoTopics()
        topics().html("")
        topics().append(template.topicForm())
        topics().append("No topics")
      when "initial topics timeout"
        topics().html("")
        topics().append(template.topicForm())
        topics().append("No topics found")
      when "no messages found"
        if chatState.isLoadingInitialMessages
          if chatState.newMessageArrivedWhileLoading?
            showMessagesInfo("No initial messages found", 3000)
          else
            showMessagesInfoOnly("No messages in this topic")
        chatState.handleNoMessages()
      when "messages pager timeout"
        showMessagesInfo("service took too long to respond")
      when "init error"
        showMessagesInfoOnly("there was an error while initializing the service")
      else
        console.log(message)

  ws.onerror = (event) ->
    console.log "WS error: " + event

  ws.onclose = (event) ->
    console.log "WS closed: " + event.code + ": " + event.reason + " " + event

  window.onbeforeunload = ->
    ws.onclose = ->
    ws.close()

  msgform().submit (event) ->
  	if not chatState.currentTopic?
  	  alert "You're not subscribed to any topic."
  	  return
    event.preventDefault()
    message = { type: "message", topic: chatState.currentTopic, msg: comment().val() }
    if messageExists()
      ws.send(JSON.stringify(message))
      resetForm()
      disableConfirmButton()

  createTopicForm().submit (event) ->
    event.preventDefault()
    message = topicNameEl().val()
    if topicNameEntered()
      ws.send(JSON.stringify(message))
      resetTopicForm()

  messageOnLeft = (u, m) ->
    template.messageOnLeft(messageInfo(u, m))

  topicsOnLeft = (topicName, topicId) ->
    template.topicsOnLeft(topicInfo(topicName, topicId))

  messageInfo = (user, message) ->
    user : user,
    message : message

  topicInfo = (topic, topicId) ->
    topicName: topic,
    topicId: topicId

  showMessagesInfoOnly = (text, millis) ->
    doShowMessagesInfo(text, millis, true)

  showMessagesInfo = (text, millis) ->
    doShowMessagesInfo(text, millis, false)

  doShowMessagesInfo = (text, millis, replaceContent) ->
    if replaceContent
      messages().html("")
    if messagesInfo().length
      messagesInfo().html(text)
    else
      elem = template.messagesInfo({text: text})
      messages().prepend(elem)
    if millis?
      setTimeout () ->
        messagesInfo().first().remove()
      , millis

  topics().on 'click', '.subscribe', (event) ->
    event.preventDefault()
    el = $(event.target)
    topicId = el.data("topic-id")
    topicName = chatState.topicNames[topicId]
    chatState.handleTopicChange topicName
    message = {type: "subscribe", topic: topicName}
    ws.send(JSON.stringify(message))
    comment().prop "disabled", false
    enableConfirmButton()
    $('.row-offcanvas').removeClass('active')
    topics().find(".active").removeClass("active")
    el.addClass("active")
    currentTopicEl().html chatState.currentTopic
    showMessagesInfoOnly("Loading messages...")

  key_enter = 13

  comment().keyup (event) ->
    if chatState.currentTopic? && messageExists()
      enableConfirmButton()
    else
      disableConfirmButton()
    if event.which is key_enter && !event.shiftKey
      event.preventDefault()
      if messageExists()
        msgform().submit()

  messages().on 'click', '#older-btn', (event) ->
  	if not chatState.currentTopic?
  	  alert "You're not subscribed to any topic."
  	  return
    date = chatState.getDateToQueryOlder()
    message = { "type": "pager", "topic": chatState.currentTopic, "direction": "older", "date": date }
    olderButton().html("loading...")
    ws.send(JSON.stringify(message))

class ChatState
  constructor: () ->
    @topicNames = {}
    @currentTopic = undefined
    @oldestMessage = undefined
    @oldestMessageDate = undefined

  handleNewTopic: (info) ->
    if @noTopics?
      delete @noTopics

  handleTopicsList: (lst) ->
    if lst.length
      if @noTopics?
        delete @noTopics
    else
      this.handleNoTopics()

  handleNoTopics: ->
    @noTopics = true

  handleNewMessage: (info) ->
    if @noMessages?
      @oldestMessage = info.msg
      @oldestMessageDate = info.creationDate
      delete @noMessages
    if @isLoadingInitialMessages?
      @newMessageArrivedWhileLoading = true
      if not @fastMessages?
        @fastMessages = []
      @fastMessages.push(info.msg)

  handleNewMessages: (info, func) ->
    msgs = info.messages
    if @isLoadingInitialMessages? and @newMessageArrivedWhileLoading?
      msgs = this.mergeMessages(msgs, @fastMessages)
      delete @fastMessages

    if not @isLoadingInitialMessages
      [withoutLast..., last] = msgs
      if shallowEquals(@oldestMessage, last)
        msgs = withoutLast

    func(msgs)

    [first, ...] = info.messages
    if first?
      @oldestMessage = first
      @oldestMessageDate = first.creationDate
    if @isLoadingInitialMessages?
      delete @isLoadingInitialMessages
      if @newMessageArrivedWhileLoading?
        delete @newMessageArrivedWhileLoading
    if @noMessages?
      delete @noMessages

  handleNoMessages: ->
    @noMessages = true
    if @isLoadingInitialMessages?
      delete @isLoadingInitialMessages
      if @newMessageArrivedWhileLoading?
        delete @noMessages
        delete @newMessageArrivedWhileLoading
        delete @fastMessages

  getDateToQueryOlder: () ->
    return @oldestMessageDate

  handleTopicChange: (topicName) ->
    @isLoadingInitialMessages = true
    @currentTopic = topicName
    delete @oldestMessage
    delete @oldestMessageDate
    if @newMessageArrivedWhileLoading?
      delete @newMessageArrivedWhileLoading
    if @fastMessages?
      delete @fastMessages
    if @noMessages?
      delete @noMessages

  mergeMessages: (initialMsgs, fastMsgs) ->
    msgs = [initialMsgs..., fastMsgs...]
    msgs.sort (a, b) ->
      a.creationDate - b.creationDate
    [head, tail...] = msgs
    result = tail.reduce (arr, elem) ->
      [..., last] = arr
      if not shallowEquals(last, elem)
        arr.push(elem)
      arr
    , [head]
    result

shallowEquals = (a, b) ->
  akeys = Object.getOwnPropertyNames(a)
  bkeys = Object.getOwnPropertyNames(b)
  if akeys.length != bkeys.length
    return false
  hadUndefineds = false
  result = akeys.reduce (res, key) =>
    if not res
      return false
    aval = a[key]
    if aval != b[key]
      return false
    if aval == undefined
      hadUndefineds = true
    res
  , true
  if not result
    return false
  if not hadUndefineds
    return true
  else
    equalAsSets(akeys, bkeys)

equalAsSets = (a, b) ->
  if a.length != b.length
    return false
  func = (first, second) ->
    if first > second
      1
    else if second > first
      -1
    else
      0
  return "#{a.sort(func)}" == "#{b.sort(func)}"


getOffsetMinusScroll = ( el ) ->
    _x = 0
    _y = 0
    while el? && !isNaN( el.offsetLeft ) && !isNaN( el.offsetTop )
        _x += el.offsetLeft - el.scrollLeft
        _y += el.offsetTop - el.scrollTop
        el = el.offsetParent
    return { top: _y, left: _x }

getOffset = ( el ) ->
    _x = 0
    _y = 0
    while el? && !isNaN( el.offsetLeft ) && !isNaN( el.offsetTop )
        _x += el.offsetLeft
        _y += el.offsetTop
        el = el.offsetParent
    return { top: _y, left: _x }

window.getOffset = getOffset
