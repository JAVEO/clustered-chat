msgform = -> $("#msgform")
createTopicForm = -> $("#topicform")
comment = -> $("#comment")
topicNameEl = -> $("#topicName")
currentTopicEl = -> $("#current-topic")
confirmButton = -> $("#sendMessageButton")
createTopicButton = -> $("#createTopicButton")
subscribeButton = -> $(".subscribe")
conversation = -> $("#conversation #messages")
messages = -> $("#messages")
topics = -> $("#topics")
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

niceScrolls = ->
  conversation().niceScroll
    background: "#eee"
    cursorcolor: "#ddd"
    cursorwidth: "10px"
    autohidemode: false
    horizrailenabled: false
  topics().niceScroll
    background: "#eee"
    cursorcolor: "#ddd"
    cursorwidth: "10px"
    autohidemode: false
    horizrailenabled: false

init = ->
  disableConfirmButton()
  niceScrolls()

$ ->
  init()

  templateScript =
    messageOnLeft: messageOnLeftTemplate().html(),
    topicsOnLeft: topicsOnLeftTemplate().html(),
    olderButton: olderButtonTemplate().html()

  template =
    messageOnLeft: Handlebars.compile(templateScript.messageOnLeft),
    topicsOnLeft: Handlebars.compile(templateScript.topicsOnLeft),
    olderButton: Handlebars.compile(templateScript.olderButton)

  chatState = new ChatState

  ws = new WebSocket $("body").data("ws-url")
  ws.onmessage = (event) ->
    message = JSON.parse event.data
    switch message.type
      when "messages"
        if message.query.topic != chatState.currentTopic
          return
        if chatState.oldestMessageState? and message.query.date != chatState.oldestMessageDate
          return
        chatState.handleNewMessages message
        oldFirstMessageElem = messages().find(".chat-msg").first()
        hadOldFirstElem = oldFirstMessageElem.length > 0
        oldPositionTop = 0
        if hadOldFirstElem
          oldPositionTop = oldFirstMessageElem.position().top - messages().position().top
        olderButtonCont = olderButtonContainer()
        if olderButtonCont.length
          olderButtonCont.remove()
        messagesHeightOld = messages().prop("scrollHeight")
        message.messages.slice().reverse().forEach (msg) ->
          messages().prepend(messageOnLeft(msg.user, msg.text))
        if not message.isLast
          messages().prepend(template.olderButton())
        if hadOldFirstElem
          messagesHeightNew = messages().prop("scrollHeight")
          messages().scrollTop(messagesHeightNew - messagesHeightOld - oldPositionTop)
        else
          messages().scrollTop(messages().prop("scrollHeight"))
      when "message"
        messages().append(messageOnLeft(message.user, message.text))
        messages().scrollTop(messages().prop("scrollHeight"))
      when "topicName"
        chatState.topicNames[message.topicId] = message.topicName
        topics().append(topicsOnLeft(message.topicName, message.topicId))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "topics"
        message.topics.forEach (topic) ->
          chatState.topicNames[topic.id] = topic.name
          topics().append(topicsOnLeft(topic.name, topic.id))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "messages pager timeout"
        messages().html("service took too long to respond")
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
  	if !chatState.currentTopic
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

  topics().on 'click', '.subscribe', (event) ->
    el = $(event.target)
    topicId = el.data("topic-id")
    topicName = chatState.topicNames[topicId]
    chatState.currentTopic = topicName
    message = {type: "subscribe", topic: topicName}
    ws.send(JSON.stringify(message))
    comment().prop "disabled", false
    enableConfirmButton()
    oldActive = topics().find(".subscribe.active")
    if oldActive
      oldActive.removeClass("active")
      oldActive.removeClass("label-info")
      oldActive.addClass("label-default")
      oldActive.prop "disabled", false
      oldActive.html "subscribe"
    el.addClass("active")
    el.removeClass("label-default")
    el.addClass("label-info")
    el.prop "disabled", true
    el.html "active"
    currentTopicEl().html chatState.currentTopic
    clearChat()

  clearChat = ->
    messages().html("")

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
    @oldestMessageDate = undefined

  handleNewMessages: (info) ->
    [first, ...] = info.messages
    if first?
      @oldestMessageDate = first.creationDate["$date"]

  getDateToQueryOlder: () ->
    return @oldestMessageDate
